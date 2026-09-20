package com.shareconnectsave.connection.domain;

// Open/Closed Principle (SOLID-O) at the code level, mirroring the schema-level
// choice in V001__connection_service_initial_schema.sql: the DB column is a
// plain NVARCHAR, not a CHECK constraint enumerating values, precisely so that
// "what counts as a valid status" lives here instead. Adding a future status
// (e.g. a CANCELLED state) means adding a constant to this enum and touching
// the state-machine code that switches on it — never a schema migration to
// widen a constraint list.
public enum ConnectionStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED
}
