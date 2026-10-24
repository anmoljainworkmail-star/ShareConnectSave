package com.shareconnectsave.admin_service.client;

// Thrown by a @CircuitBreaker fallbackMethod, NEVER by a plain call failure
// path directly — deliberately a DIFFERENT outcome from a client method
// returning null. A null return (see UserServiceClient.getUser's own
// comment) means "I successfully asked, and the honest answer is this
// doesn't exist" (a genuine 404) — a business fact. This exception means "I
// could not get an honest answer at all" (the circuit is open, or the real
// call failed) — an infrastructure fact. Collapsing the two into the same
// null would make ReviewQueueServiceImpl unable to tell "this user does not
// exist" apart from "Rating/Report Service happens to be down right now",
// which is exactly the ambiguity the ticket's own "Partial-failure
// degradation" spec exists to avoid (an admin must never see a blank/missing
// slice and assume it means "there is nothing here").
//
// Single Responsibility (SOLID-S): one small, shared exception type for all
// three clients (UserServiceClient.getUser, RatingServiceClient.getTrustScore,
// ReportServiceClient.getReportHistory) rather than three near-identical
// exception classes — ReviewQueueServiceImpl catches exactly this one type
// at each of the three aggregation call sites in GET /admin/users/{id}.
public class UpstreamUnavailableException extends RuntimeException {
    public UpstreamUnavailableException(String upstreamService, Throwable cause) {
        super(upstreamService + " is unavailable (circuit open or call failed)", cause);
    }
}
