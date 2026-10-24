package com.shareconnectsave.admin_service.client;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

// Pattern: MapStruct (see UserProfileMapper's own comment in this same
// package for the plain-English introduction). Lives here for the same
// package-private-nested-record reason: RatingServiceClientImpl.
// TrustScoreQueryResponseDto is only visible to a generated implementation
// class in this same package.
//
// userId (Long -> String) and trustScore (BigDecimal -> double) need no
// @Mapping override despite the type change: MapStruct has built-in implicit
// conversions for exactly these pairs (wrapper-numeric <-> String,
// BigDecimal <-> primitive/wrapper numerics), the same way it already
// handles report-service's enum<->String conversions with zero extra
// annotations. One real difference worth noting from the hand-written code
// it replaces: the old `String.valueOf(response.userId())` would have
// printed the literal string "null" if userId were ever null; MapStruct's
// generated null-check instead leaves the target's userId null in that case
// — strictly safer, not just equivalent.
//
// isTrusted -> trusted DOES need an explicit @Mapping: the two sides don't
// share a property name (the wire DTO's field is literally named isTrusted,
// matching Rating Service's JSON; TrustScoreSnapshot's own field is named
// trusted, matching how every call site in this codebase already reads it,
// e.g. ReviewQueueServiceImpl's `snapshot.trusted()`), so MapStruct's
// automatic same-name matching can't find it on its own — the same situation
// rating-service's own RatingMapper already has an @Mapping for (its `tags`
// parameter).
@Mapper(componentModel = "spring")
interface TrustScoreSnapshotMapper {

    @Mapping(target = "trusted", source = "isTrusted")
    RatingServiceClient.TrustScoreSnapshot toTrustScoreSnapshot(RatingServiceClientImpl.TrustScoreQueryResponseDto dto);
}
