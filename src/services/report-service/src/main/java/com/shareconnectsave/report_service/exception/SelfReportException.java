package com.shareconnectsave.report_service.exception;

// Guard against a user reporting themselves: without this check, the
// reportedId count/escalation machinery could be driven entirely by one
// user spamming reports against their own id, which would either do nothing
// useful or (worse) let a user manufacture a false "this profile is
// suspicious" signal about themselves. Thrown from
// ReportServiceImpl.fileReport before any DB read/write happens, same guard
// position as connection-service's SelfConnectionRequestException.
public class SelfReportException extends RuntimeException {
    public SelfReportException(String userId) {
        super("User " + userId + " cannot report themselves");
    }
}
