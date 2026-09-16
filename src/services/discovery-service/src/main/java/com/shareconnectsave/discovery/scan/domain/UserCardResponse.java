package com.shareconnectsave.discovery.scan.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

// Mirrors User Service's PublicUserProfileDto.cs field-for-field (id, name,
// photo_url, identity_badge), plus distance_km, which Discovery Service adds
// itself — the one piece of this card User Service could never supply,
// since only Discovery ever knows the caller's live position.
//
// trust_score is deliberately NOT a field here, matching
// PublicUserProfileDto.cs's own comment about rating/destination: Rating
// Service (which would compute it) is Phase 7 and does not exist in this
// codebase yet. Adding a placeholder/mock value now would violate this
// project's "no half-finished implementations, don't fabricate data for
// services that don't exist" rule — the field is added the day Rating
// Service actually ships one, not before.
//
// status is likewise absent by design: Discovery has no separate status
// concept (see ScanQueryServiceImpl's comment on scan:active_sessions Set
// membership being the structural "looking" signal), and gender is never
// returned here at all — Women-only mode is a server-side filter predicate
// only, never exposed in an API response to another user.
//
// unavailable (T027): Circuit Breaker degraded-response marker. Every real
// profile (a live User Service fetch, or a cache hit of one) sets this
// false; DiscoveryCacheService sets it true only for the placeholder card it
// manufactures itself when User Service's circuit is open or a call to it
// failed. Callers (ScanQueryServiceImpl) still include the card in results —
// unlike a genuine 404 (still modeled as a null UserCardResponse, "omit this
// candidate"), "User Service is having a bad day" is not a reason to make a
// real nearby match vanish from someone's radar, just a reason to show it
// with less detail than usual.
public record UserCardResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("name") String name,
        @JsonProperty("photo_url") String photoUrl,
        @JsonProperty("identity_badge") boolean identityBadge,
        @JsonProperty("distance_km") double distanceKm,
        @JsonProperty("unavailable") boolean unavailable) {
}
