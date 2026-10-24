package com.shareconnectsave.admin_service.mapper;

import org.mapstruct.Mapper;

import com.shareconnectsave.admin_service.client.ReportServiceClient.ReportHistoryItem;
import com.shareconnectsave.admin_service.dto.ReportHistoryEntry;

// Pattern: MapStruct (see client.UserProfileMapper's own comment for the
// plain-English introduction). This one lives in its own `mapper` package,
// not alongside a client — both ReportHistoryItem (a PUBLIC nested record of
// the PUBLIC ReportServiceClient interface) and ReportHistoryEntry
// (admin-service's own public outward DTO) are visible from anywhere, so
// there is no package-private constraint forcing this mapper to live next to
// either one, matching report-service's/rating-service's own "mapper gets
// its own package" convention.
//
// No @Mapping needed: id/reporterId/reason/status/createdAt match by name and
// type exactly between the two records (see ReportHistoryEntry's own class
// comment on why it deliberately mirrors Report Service's wire shape here
// rather than diverging from it).
@Mapper(componentModel = "spring")
public interface ReportHistoryEntryMapper {

    ReportHistoryEntry toReportHistoryEntry(ReportHistoryItem item);
}
