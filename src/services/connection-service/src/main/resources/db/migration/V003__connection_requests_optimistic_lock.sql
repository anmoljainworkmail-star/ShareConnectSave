-- V003__connection_requests_optimistic_lock.sql
--
-- Flyway naming convention: V{version}__{description}.sql, same rule as
-- V001/V002 — this file is checksummed into Flyway's history table on first
-- run and must never be edited after that point; a further fix belongs in a
-- new V004, not here.
--
-- Optimistic Concurrency Control: backs ConnectionRequest.version
-- (@Version). Two concurrent acceptConnection() calls for the same
-- connection id can both pass every guard clause before either commits —
-- without this column, both UPDATEs would succeed and produce two
-- connection.accepted outbox rows for one logical accept. Hibernate uses
-- this column as "AND version = ?" in its UPDATE's WHERE clause, so the
-- losing concurrent writer's UPDATE matches zero rows and Hibernate raises
-- OptimisticLockingFailureException instead of silently double-committing.
ALTER TABLE connection_requests
    ADD version BIGINT NOT NULL DEFAULT 0;
