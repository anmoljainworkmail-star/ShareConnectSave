package com.shareconnectsave.rating.trustscore;

// Dependency Inversion (SOLID-D): every T044 Kafka consumer that needs a
// trust score recalculated (RatingSubmittedConsumer, ReportFiledConsumer)
// depends on THIS interface, never on TrustScoreRecalculationServiceImpl
// directly - same "controller/consumer depends on the interface, not the
// impl" shape the java-spring-boot skill requires for RatingService/
// RatingServiceImpl, generalised here from HTTP controllers to Kafka
// consumers ("thin controllers require a service layer" applies just as much
// to a thin @KafkaListener method).
//
// Single Responsibility (SOLID-S): recalculating-and-persisting-and-
// publishing a trust score is ONE job, shared by two different triggers
// (a new rating, a report crossing the suspension threshold). Without this
// shared seam, that orchestration logic would be duplicated verbatim inside
// both consumer classes - this interface is what lets both call the same
// implementation instead.
public interface TrustScoreRecalculationService {

    // reportCount is supplied by the CALLER, never looked up inside this
    // service - RatingSubmittedConsumer passes the last known count already
    // sitting in trust_scores (see TrustScore.reportCount's own comment),
    // ReportFiledConsumer passes the fresh report_count_for_target straight
    // off the event it just received. Neither caller needs to know how the
    // other sources its number.
    void recalculate(Long userId, long reportCount);
}
