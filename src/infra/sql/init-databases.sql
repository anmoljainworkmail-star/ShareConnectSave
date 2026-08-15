-- init-databases.sql
-- Run once by the `sqlserver-init` job (see docker-compose.yml) after SQL Server
-- reports healthy. Creates one empty database per service.
--
-- Pattern: Database per Service — this script is the literal enforcement of the
-- rule. SQL Server itself does not stop one service's code from opening a
-- connection to another service's database; the boundary is upheld by giving
-- each service only the connection string to its own database (see the root
-- .env.example / docker-compose.override.yml) and by convention/code review,
-- never by cross-database joins. Without this separation, "just add a quick
-- join across services" becomes an easy shortcut that quietly recreates a
-- monolith on top of what looks like a microservice architecture.
--
-- Naming follows the "<Service>ServiceDb" convention already used by the
-- connection strings wired in docker-compose.override.yml and the root
-- .env.example (T001) — NOT the snake_case names in this ticket's original
-- spec, to keep the SA login able to actually reach what the services expect.

IF DB_ID('UserServiceDb') IS NULL CREATE DATABASE UserServiceDb;
IF DB_ID('DiscoveryServiceDb') IS NULL CREATE DATABASE DiscoveryServiceDb;
IF DB_ID('ConnectionServiceDb') IS NULL CREATE DATABASE ConnectionServiceDb;

-- Chat Service's primary store is MongoDB (ephemeral messages, TTL index) — this
-- SQL database stays empty by design. It exists only so "one database per
-- service" holds uniformly, even for a service whose real data lives elsewhere;
-- a future need (e.g. relational lookup data) has somewhere to go without a
-- fresh provisioning step.
IF DB_ID('ChatServiceDb') IS NULL CREATE DATABASE ChatServiceDb;

IF DB_ID('RatingServiceDb') IS NULL CREATE DATABASE RatingServiceDb;
IF DB_ID('NotificationServiceDb') IS NULL CREATE DATABASE NotificationServiceDb;

-- Report Service's primary store is MongoDB (report queue/escalation) — same
-- rationale as Chat above.
IF DB_ID('ReportServiceDb') IS NULL CREATE DATABASE ReportServiceDb;

IF DB_ID('AdminServiceDb') IS NULL CREATE DATABASE AdminServiceDb;
GO
