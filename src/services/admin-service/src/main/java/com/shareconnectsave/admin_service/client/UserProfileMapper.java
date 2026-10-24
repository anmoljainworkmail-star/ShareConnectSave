package com.shareconnectsave.admin_service.client;

import org.mapstruct.Mapper;

// Pattern: MapStruct — plain English, for anyone meeting it for the first
// time: instead of hand-writing "copy field A to field B, copy field C to
// field D" for every pair of near-identical classes, you write an empty
// interface naming the two types, and a compile-time code generator (not
// reflection, not a runtime library call) writes that copying code for you,
// the same way a Java compiler writes a record's constructor/getters from
// just its component list. MapStruct is this project's existing generator
// (already used by report-service's ReportMapper, rating-service's
// RatingMapper, connection-service's ConnectionMapper) — componentModel =
// "spring" is what makes the generated implementation a normal injectable
// Spring bean, not something each call site has to new() up itself.
//
// Lives in THIS package (client), not a separate `mapper` package like the
// other three services' mappers — UserServiceClientImpl.SuspendedUserDto/
// UserProfileDto are deliberately package-private nested records (see that
// class's own header comment on why User Service's wire shape never leaks
// outside this one file), and MapStruct's generated implementation class can
// only see package-private types from inside the same package.
//
// No @Mapping annotations needed: every source/target component name below
// matches exactly (user_id/display_name/account_status/suspended_at are
// already resolved to plain userId/displayName/accountStatus/suspendedAt by
// Jackson's @JsonProperty before these records ever reach this mapper).
@Mapper(componentModel = "spring")
interface UserProfileMapper {

    UserServiceClient.SuspendedUserSummary toSuspendedUserSummary(UserServiceClientImpl.SuspendedUserDto dto);

    UserServiceClient.UserProfileSummary toUserProfileSummary(UserServiceClientImpl.UserProfileDto dto);
}
