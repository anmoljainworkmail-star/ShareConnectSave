package com.shareconnectsave.admin_service.client;

import org.mapstruct.Mapper;

// Pattern: MapStruct (see UserProfileMapper's own comment in this same
// package for the plain-English introduction). Lives here, not a separate
// `mapper` package, for the identical reason: ReportServiceClientImpl.ReportDto
// is a package-private nested record, only visible to a generated
// implementation class that lives in this same package.
//
// No @Mapping needed: id/reporterId/reason/status/createdAt match by name and
// type exactly. ReportDto.reportedId has no corresponding target property —
// an unmapped SOURCE property, which MapStruct allows silently (it only
// warns/fails on an unmapped TARGET property, same as report-service's own
// ReportMapper comment explains for Report.eventPayload/outboxPublishedAt).
@Mapper(componentModel = "spring")
interface ReportHistoryItemMapper {

    ReportServiceClient.ReportHistoryItem toReportHistoryItem(ReportServiceClientImpl.ReportDto dto);
}
