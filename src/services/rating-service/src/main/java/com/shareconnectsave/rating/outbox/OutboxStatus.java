package com.shareconnectsave.rating.outbox;

// Open/Closed (SOLID-O), same reasoning as connection-service's own
// OutboxStatus (T032): a future tri-state (e.g. DLQ) is added to this enum
// and to OutboxRelay's handling of it - never to a SQL CHECK constraint,
// which is why the outbox.status column in V002 is a plain NVARCHAR rather
// than an enumerated constraint.
public enum OutboxStatus {
    PENDING,
    PROCESSED
}
