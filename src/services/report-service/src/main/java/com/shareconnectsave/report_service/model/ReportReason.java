package com.shareconnectsave.report_service.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.shareconnectsave.report_service.exception.InvalidReportReasonException;

/**
 * Open/Closed (SOLID O): report reason categories live here as enum
 * constants, not as a free-text string validated by scattered if/else
 * checks scattered across controllers/services. Adding a new reason later
 * means adding one line to this enum — no existing validation logic has to
 * change, and every switch/when over ReportReason the compiler can check
 * will immediately flag the new case as unhandled instead of silently
 * falling through.
 *
 * Wire-format mapping (T052): contracts/openapi/report-service.yaml's
 * ReportReason schema uses human-readable Title Case strings ("Harassment",
 * "Fake profile", ...) — not valid Java identifiers — so this enum cannot
 * rely on Jackson's default enum (de)serialization, which would read/write
 * the Java constant name (HARASSMENT) instead of the contract's actual
 * string. @JsonValue/@JsonCreator make that mapping explicit and
 * bidirectional, exactly the technique rating-service's RatingTag already
 * uses for the same reason. ONE enum, reused for BOTH the HTTP
 * ReportCreateDto.reason field and the Kafka ReportFiledEvent.reason field
 * — a single source of truth serialized two ways, not two separate
 * enums that could silently drift apart.
 *
 * Known contract drift (deliberately NOT reconciled here): contracts/kafka/
 * report.filed.schema.json's own reason enum (harassment, fake_profile,
 * inappropriate_content, scam, abusive_behaviour, spam, other) does not
 * match this enum, REQUIREMENTS.md §8, or the OpenAPI ReportReason schema —
 * it's missing ASKED_FOR_MONEY and has three categories that exist nowhere
 * else in this project. The OpenAPI contract + this enum are authoritative;
 * SCAM/ABUSIVE_BEHAVIOUR/SPAM are deliberately not added here. This is a
 * /validate-contracts finding for the Tech Lead to fix in the schema file
 * itself, not something to paper over in code.
 */
public enum ReportReason {
    HARASSMENT("Harassment"),
    FAKE_PROFILE("Fake profile"),
    INAPPROPRIATE_CONTENT("Inappropriate content"),
    ASKED_FOR_MONEY("Asked for money"),
    OTHER("Other");

    private final String wireValue;

    ReportReason(String wireValue) {
        this.wireValue = wireValue;
    }

    // @JsonValue: whenever Jackson serializes a ReportReason (as part of
    // ReportResponse or ReportFiledEvent), it writes THIS string instead of
    // the enum constant's Java name — one mapping, used identically on both
    // the HTTP response body and the Kafka event payload.
    @JsonValue
    public String toWireValue() {
        return wireValue;
    }

    // @JsonCreator: the inverse mapping, used when Jackson deserializes
    // ReportCreateDto.reason from the incoming request body. An unrecognized
    // wire string throws InvalidReportReasonException, which
    // ReportExceptionHandler maps to a clean 400 INVALID_REPORT_REASON —
    // same idiom RatingTag.fromWireValue/InvalidRatingTagException already
    // establishes in rating-service.
    @JsonCreator
    public static ReportReason fromWireValue(String wireValue) {
        for (ReportReason reason : values()) {
            if (reason.wireValue.equals(wireValue)) {
                return reason;
            }
        }
        throw new InvalidReportReasonException(wireValue);
    }
}
