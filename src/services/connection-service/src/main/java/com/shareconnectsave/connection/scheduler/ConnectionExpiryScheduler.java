package com.shareconnectsave.connection.scheduler;

import com.shareconnectsave.connection.ConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// Single Responsibility (SOLID-S): this class owns exactly one decision —
// WHEN to check for overdue connection requests (every 60_000 ms). It has
// no idea what "overdue" means, what a valid state transition looks like,
// or what a connection.expired event contains — all of that is
// ConnectionService's job, same split OutboxRelay/IOutboxService already
// uses elsewhere in this service. This is also the first @Scheduled job in
// the whole platform (Phase 5's "TTL expiry runs on schedule").
//
// This is where the per-request loop and its try/catch live, NOT inside
// ConnectionServiceImpl — see ConnectionService.expireConnectionRequest's
// own comment for the Spring self-invocation reason a real per-request
// @Transactional boundary requires crossing an actual bean boundary like
// this one.
@Component
@RequiredArgsConstructor
@Slf4j
public class ConnectionExpiryScheduler {

    private final ConnectionService connectionService;

    @Scheduled(fixedRate = 60_000)
    public void expireOverdueConnectionRequests() {
        List<Long> overdueConnectionIds = connectionService.findOverdueConnectionRequestIds();

        // Resilience: one bad row must not take the rest of this tick down
        // with it. Each iteration is deliberately its own try/catch — NOT
        // one try/catch wrapped around the whole loop — precisely so a
        // single failing expireConnectionRequest call (e.g. an unexpected
        // constraint violation on one row) is logged and skipped while
        // every other overdue request in this same tick still gets
        // processed. A failure here is always the LOCAL DB transaction for
        // that one row failing to commit — never a Kafka/OutboxRelay
        // failure, since IOutboxService.publish only ever writes a
        // PENDING row to this service's own outbox table; OutboxRelay's
        // later, separate poll-and-publish step has its own retry loop and
        // cannot make this method throw.
        for (Long connectionId : overdueConnectionIds) {
            try {
                connectionService.expireConnectionRequest(connectionId);
            } catch (Exception e) {
                log.error("Failed to expire connection request {}", connectionId, e);
            }
        }

        if (!overdueConnectionIds.isEmpty()) {
            log.info("Expired {} overdue connection request(s)", overdueConnectionIds.size());
        }
    }
}
