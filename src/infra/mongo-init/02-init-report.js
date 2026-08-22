// =====================================================================
// T007 — Report Service MongoDB init script.
//
// Same execution model as 01-init-chat.js: the official mongo image runs
// every *.js file in /docker-entrypoint-initdb.d/ once, in filename order,
// the first time the container starts against an empty data volume.
// Numbered "02-" so it always runs after chat's "01-" script, though the
// two are independent (different databases) and order between them doesn't
// actually matter here — the numbering is just a readability convention.
//
// Pattern: Database per Service — Report Service is the only service
// permitted to read/write this database. Admin Service (which needs to see
// reports for its review queue) reaches this data over HTTP, never via a
// direct DB connection — see the Database per Service row in CLAUDE.md.
// =====================================================================

// Naming note: same reasoning as 01-init-chat.js — the ticket text says
// "report_db", but T004 already wired report-service's actual connection
// string to ReportServiceDb (docker-compose.override.yml:
// SPRING_DATA_MONGODB_URI=mongodb://mongodb:27017/ReportServiceDb, and
// REPORT_MONGODB_URI in root .env.example). Using the name the service
// actually connects to, not the ticket's literal string — otherwise this
// script's indexes (including any future TTL/lifecycle index) would sit on
// a database report-service never opens.
const reportDb = db.getSiblingDB('ReportServiceDb');

// Idempotency guard — see 01-init-chat.js for why this checks existence
// first instead of catching NamespaceExists: readability, and it matches
// the ticket's explicit "do not drop collections on re-init" instruction.
function ensureCollection(database, name) {
  const alreadyExists = database.getCollectionNames().indexOf(name) !== -1;
  if (!alreadyExists) {
    database.createCollection(name);
  }
}

ensureCollection(reportDb, 'reports');

// Two separate single-field indexes, matching the two access patterns the
// ticket calls out explicitly:
//   1. Admin Service's review queue scans "all reports filed against this
//      user" -> reported_id
//   2. Escalation/triage views sort/paginate by "most recently filed"
//      -> filed_at
// createIndex() is naturally idempotent for an unchanged definition, so no
// extra existence check is needed here (unlike the TTL index in
// 01-init-chat.js, where a CHANGED option value would throw).
reportDb.reports.createIndex({ reported_id: 1 });
reportDb.reports.createIndex({ filed_at: 1 });

// No TTL index on `reports` by design: unlike chat messages, reports are a
// moderation/audit record — Report Service's whole job (escalation queue,
// admin review) depends on this data persisting, not expiring. The privacy
// hard requirement ("no message history") applies to chat content, not to
// the fact that a report was filed.

print('[mongo-init] ReportServiceDb ready — reports collection initialized.');
