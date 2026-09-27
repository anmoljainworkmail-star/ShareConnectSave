package com.shareconnectsave.connection.outbox;

// Open/Closed (SOLID-O), same reasoning as ConnectionStatus: a future
// tri-state (e.g. DLQ, per the kafka-outbox skill's Dead Letter Queue
// section) is added to this enum and the relay's own switch/if logic —
// never to a SQL CHECK constraint, which is why the outbox.status column in
// V002 is a plain NVARCHAR rather than an enumerated constraint.
public enum OutboxStatus {
    PENDING,
    PROCESSED
}
