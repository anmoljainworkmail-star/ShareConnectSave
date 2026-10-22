package com.shareconnectsave.report_service.mapper;

import org.mapstruct.Mapper;

import com.shareconnectsave.report_service.dto.ReportResponse;
import com.shareconnectsave.report_service.model.Report;

// Pattern: MapStruct generates this implementation at compile time — zero
// reflection overhead, and (same two classic hand-rolled-mapper bugs
// connection-service's ConnectionMapper comment calls out) it fails the
// build instead of silently defaulting a field to null whenever
// Report/ReportResponse's shape drifts apart.
//
// Only toResponse exists here — no toEntity. ReportServiceImpl builds the
// Report document itself via Report.builder() (same convention
// ConnectionServiceImpl.createConnection uses for ConnectionRequest):
// reportedId/reason/connectionId/details come straight off the validated
// DTO, but id (app-minted), status (defaulted), createdAt, and the outbox
// fields (eventPayload/outboxPublishedAt) all need service-layer logic a
// generic DTO->entity mapping would just get in the way of.
@Mapper(componentModel = "spring")
public interface ReportMapper {

    // No @Mapping annotations needed: ReportResponse's component names match
    // Report's property names exactly, so MapStruct wires each one by name
    // on its own. Report.eventPayload/outboxPublishedAt have no
    // corresponding target field — unmapped SOURCE properties are fine,
    // MapStruct only warns/fails on an unmapped TARGET property.
    ReportResponse toResponse(Report report);
}
