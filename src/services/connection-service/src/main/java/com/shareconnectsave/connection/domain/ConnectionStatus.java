package com.shareconnectsave.connection.domain;

// Open/Closed Principle (SOLID-O) at the code level, mirroring the schema-level
// choice in V001__connection_service_initial_schema.sql: the DB column is a
// plain NVARCHAR, not a CHECK constraint enumerating values, precisely so that
// "what counts as a valid status" lives here instead. Adding a future status
// (e.g. a CANCELLED state) means adding a constant to this enum and touching
// the state-machine code that switches on it — never a schema migration to
// widen a constraint list.
//
// COMPLETED is exactly that "add a constant" case. Before it existed an
// ACCEPTED connection had no way to END: nothing moved it out of ACCEPTED, so
// "does this user have an active connection" (status = ACCEPTED) stayed true
// forever after the pair met, and the one-active-connection-at-a-time guard
// in createConnection kept rejecting every new request with
// ACTIVE_CONNECTION_EXISTS. COMPLETED means "the conversation is over, however
// it ended" (met, timeout, or admin close) - it is terminal, like DECLINED and
// EXPIRED, and is reached only from ACCEPTED, driven by the chat.closed event
// (see ChatClosedConsumer), never by a user-facing endpoint.
public enum ConnectionStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    COMPLETED
}
