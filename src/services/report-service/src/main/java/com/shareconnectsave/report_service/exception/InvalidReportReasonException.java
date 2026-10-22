package com.shareconnectsave.report_service.exception;

// Thrown by ReportReason.fromWireValue when a submitted reason string is not
// a member of the ReportReason enum (Open/Closed, SOLID-O — the validation
// rule is "is this value in the set", never a hardcoded if/else chain of
// allowed strings). Same idiom as rating-service's InvalidRatingTagException.
public class InvalidReportReasonException extends RuntimeException {
    public InvalidReportReasonException(String rawReason) {
        super("'" + rawReason + "' is not a recognized report reason");
    }
}
