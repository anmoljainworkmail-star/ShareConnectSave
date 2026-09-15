package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.cache.DiscoveryEligibilityCacheService;
import com.shareconnectsave.discovery.cache.ScanCacheService;
import com.shareconnectsave.discovery.client.UserServiceClient;
import com.shareconnectsave.discovery.config.DiscoveryProperties;
import com.shareconnectsave.discovery.scan.domain.ScanLocation;
import com.shareconnectsave.discovery.scan.domain.ScanSession;
import com.shareconnectsave.discovery.scan.domain.UserCardResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// Pattern: Single Responsibility (SOLID-S) — this class does exactly one
// job, the 5-step GPS nearby-query orchestration this ticket specifies.
// Redis key/TTL mechanics live in ScanCacheService, HTTP + circuit-breaker
// mechanics live in UserServiceClient, and pure math lives in
// ScanFilterService — this class only sequences those collaborators and
// applies filter predicates in order. It never opens a RedisTemplate
// connection for its OWN cache keys or builds a WebClient itself; it does
// read two Redis keys directly (gender/women_only, below) because those are
// ScanSessionServiceImpl's keys, not ScanCacheService's — see the comment at
// that read site for why.
@Service
@Slf4j
@RequiredArgsConstructor
public class ScanQueryServiceImpl implements ScanQueryService {

    private final ScanSessionRepository scanSessionRepository;
    private final ScanCacheService scanCacheService;
    private final ScanFilterService scanFilterService;
    private final UserServiceClient userServiceClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DiscoveryEligibilityCacheService eligibilityCacheService;

    // Options pattern (T023) — one bean bound from application.yml's
    // "discovery" section (see DiscoveryProperties), injected like any other
    // collaborator instead of three separate @Value constructor parameters.
    private final DiscoveryProperties discoveryProperties;

    @Override
    public List<UserCardResponse> findNearby(Long callerUserId) {
        // Pattern: Pessimistic locking, deliberately kept SHORT here — unlike
        // stopScan/updateLocation (which wrap this same repository call in an
        // outer @Transactional specifically to WIDEN the lock's scope), this
        // method has NO @Transactional of its own. Spring Data JPA still
        // wraps every derived query method in its own short-lived, repository
        // -managed transaction by default — so the PESSIMISTIC_WRITE row lock
        // this query takes is acquired and released by the time this single
        // call returns, never held across the Redis reads and User Service
        // HTTP calls below. Widening it here (the way stopScan does) would
        // hold a SQL row lock for the duration of a network call to another
        // service — exactly the kind of latency-amplifying mistake a
        // P95 < 500ms endpoint can't afford.
        ScanSession callerSession = requireActiveSession(callerUserId);
        Long callerSessionId = callerSession.getId();

        // Cache-Aside read: a query for this exact session inside the last
        // 10s is served straight from Redis, without recomputing a single
        // filter or re-calling User Service for the same candidates.
        List<UserCardResponse> cachedResult = scanCacheService.getCachedNearbyResult(callerSessionId);
        if (cachedResult != null) {
            return cachedResult;
        }

        ScanLocation callerLocation = getLocation(callerSessionId);
        if (callerLocation == null) {
            // "What NOT to do": missing location must never crash the query
            // — a caller with no live GPS fix (client went silent, the 30s
            // location TTL expired) simply sees an empty result.
            log.warn("No cached location for caller session {} — returning empty nearby result", callerSessionId);
            return List.of();
        }

        // gender/women_only are ScanSessionServiceImpl's keys, not
        // ScanCacheService's (see that class's scope comment) — they carry
        // no TTL and are written once at /scan/start, deleted once at
        // /scan/stop, so a plain RedisTemplate read is used here rather than
        // adding cache-aside ceremony (get/miss/populate) around a value
        // that is never "recomputed", only ever looked up.
        boolean callerWomenOnly = Boolean.TRUE.equals(
                redisTemplate.opsForValue().get(ScanSessionServiceImpl.womenOnlyKey(callerSessionId)));

        Set<Object> activeSessionIds = scanCacheService.getActiveSessionIds();

        // First pass: narrow the candidate set using ONLY the session id —
        // self-exclusion and women-only (a single, always-cached Redis GET
        // each) — before touching SQL or any per-candidate location lookup.
        List<Long> preFilteredCandidateIds = new ArrayList<>();
        for (Object rawSessionId : activeSessionIds) {
            Long candidateSessionId = Long.valueOf(String.valueOf(rawSessionId));
            if (candidateSessionId.equals(callerSessionId)) {
                continue;
            }
            if (callerWomenOnly) {
                String candidateGender = (String) redisTemplate.opsForValue()
                        .get(ScanSessionServiceImpl.genderKey(candidateSessionId));
                if (!"female".equalsIgnoreCase(candidateGender)) {
                    continue;
                }
            }
            preFilteredCandidateIds.add(candidateSessionId);
        }

        // Second pass: radius check using ONLY Redis live-location data —
        // this needs no SQL access at all (distance is live-position to
        // live-position, both Redis-only), so it runs BEFORE the SQL fetch
        // below, not after. Fetching SQL rows (destination, departure time)
        // for a candidate who is about to be rejected purely by live
        // position would mean querying SQL for data this filter never
        // needed — narrowing here first means findAllById only ever pulls
        // rows for candidates already confirmed nearby. distanceKm is kept
        // per candidate so the final UserCardResponse doesn't recompute it.
        List<Long> nearbyCandidateIds = new ArrayList<>();
        Map<Long, Double> distanceKmBySessionId = new HashMap<>();
        for (Long candidateSessionId : preFilteredCandidateIds) {
            ScanLocation candidateLocation = getLocation(candidateSessionId);
            if (candidateLocation == null) {
                log.warn("No cached location for candidate session {} — skipping", candidateSessionId);
                continue;
            }
            double distanceKm = scanFilterService.distanceKm(callerLocation, candidateLocation);
            if (distanceKm > discoveryProperties.radius().km()) {
                continue;
            }
            nearbyCandidateIds.add(candidateSessionId);
            distanceKmBySessionId.put(candidateSessionId, distanceKm);
        }

        // Batch fetch, radius-narrowed and not one findById() per candidate:
        // JpaRepository.findAllById issues a single "WHERE id IN (...)"
        // query, now scoped to only the candidates the free Redis check
        // above already confirmed nearby — SQL never sees a row for anyone
        // who was going to be rejected on live position alone. A loop of
        // findById() calls here instead would be an N+1 query bug on top of
        // that, exactly the kind of per-request cost this endpoint's
        // P95 < 500ms budget can't absorb once the active-session pool is
        // more than a handful of people.
        Map<Long, ScanSession> candidateSessionsById = scanSessionRepository.findAllById(nearbyCandidateIds).stream()
                .collect(Collectors.toMap(ScanSession::getId, Function.identity()));

        List<UserCardResponse> results = new ArrayList<>();

        for (Long candidateSessionId : nearbyCandidateIds) {
            ScanSession candidateSession = candidateSessionsById.get(candidateSessionId);
            if (candidateSession == null || !candidateSession.isActive()) {
                // A session can end up in this Set without a matching active
                // SQL row only through a bug elsewhere (e.g. a crash between
                // the SQL close() commit and the SREM in stopScan) — logged
                // as a warning, not treated as a crash-worthy condition. Only
                // surfaced here for candidates already confirmed nearby —
                // this filter order trades away detecting the same anomaly
                // for a far-away ghost session, which was never going to be
                // a match anyway.
                log.warn("Session {} is in {} but has no active SQL row — skipping",
                        candidateSessionId, ScanCacheService.ACTIVE_SESSIONS_KEY);
                continue;
            }

            double distanceKm = distanceKmBySessionId.get(candidateSessionId);

            // Route overlap/departure-window are pure in-memory math on data
            // already in hand (callerLocation from earlier, candidateSession
            // from the batch fetch above) — no additional Redis or SQL call
            // either check could possibly save, so they run next, before the
            // block-list check below (up to two Redis GETs, and a WebClient
            // call to User Service on a cache miss).
            double routeOverlap = scanFilterService.routeOverlap(
                    callerLocation,
                    callerSession.getDestinationLat(), callerSession.getDestinationLng(),
                    candidateSession.getDestinationLat(), candidateSession.getDestinationLng());
            if (routeOverlap < discoveryProperties.routeOverlapThreshold()) {
                continue;
            }

            if (!scanFilterService.withinDepartureWindow(
                    callerSession.getDepartureTime(), candidateSession.getDepartureTime(),
                    discoveryProperties.departureWindowMinutes())) {
                continue;
            }

            // Candidate status = "looking": satisfied structurally by this
            // candidate's session id having been a member of
            // scan:active_sessions at all (see ScanSessionServiceImpl's
            // SADD/SREM) — there is no separate status field anywhere in
            // Discovery's own data to check here.
            Long candidateUserId = candidateSession.getUserId();

            // Eligibility gate check: only verified, active users appear in
            // discovery results. This Set is maintained by Kafka consumers
            // (T025) reacting to user.verified and trust.score.updated
            // events. A candidate not in this Set is filtered before any
            // costly operations (block-list checks, User Service calls).
            if (!eligibilityCacheService.isEligible(String.valueOf(candidateUserId))) {
                continue;
            }

            // Block list last: the most expensive remaining check (up to two
            // Redis GETs, and a WebClient call to User Service on a miss),
            // so it only ever runs for a candidate who already survived
            // every cheaper filter above, including women-only.
            if (isBlocked(callerUserId, candidateUserId) || isBlocked(candidateUserId, callerUserId)) {
                continue;
            }

            // Circuit Breaker Pattern — UserServiceClient wraps this call in
            // Resilience4j; a down/slow User Service degrades to cached
            // profile data or null (this candidate simply omitted) instead
            // of failing the entire /scan/nearby request for every other
            // match already computed above.
            UserCardResponse card = userServiceClient.getUserCard(candidateUserId, distanceKm);
            if (card != null) {
                results.add(card);
            }
        }

        scanCacheService.cacheNearbyResult(callerSessionId, results);
        return results;
    }

    // Cache-Aside Pattern, bidirectional: a candidate only passes when
    // NEITHER direction of the block relationship exists. Checking only
    // caller -> candidate would miss a candidate who has blocked the caller
    // (this ticket's own "What NOT to do" calls this out explicitly).
    private boolean isBlocked(Long ownerUserId, Long otherUserId) {
        List<Long> blockList = scanCacheService.getCachedBlockList(ownerUserId);
        if (blockList == null) {
            blockList = userServiceClient.getBlockList(ownerUserId);
            scanCacheService.cacheBlockList(ownerUserId, blockList);
        }
        return blockList.contains(otherUserId);
    }

    private ScanLocation getLocation(Long sessionId) {
        Object raw = redisTemplate.opsForValue().get(ScanSessionServiceImpl.locationKey(sessionId));
        return raw instanceof ScanLocation location ? location : null;
    }

    // Deliberately duplicates ONE repository call from
    // ScanSessionServiceImpl.requireActiveSession rather than exposing that
    // private method — the query logic here needs the full ScanSession
    // (destination, departure time), not just the session id, so this is a
    // fresh call through the same shared ScanSessionRepository, not
    // duplicated business logic.
    private ScanSession requireActiveSession(Long userId) {
        return scanSessionRepository.findFirstByUserIdAndEndedAtIsNullOrderByIdDesc(userId)
                .orElseThrow(() -> new ScanSessionNotFoundException(userId));
    }
}
