// =====================================================================
// T007 — Chat Service MongoDB init script.
//
// Runs automatically the FIRST time the `mongodb` container starts against
// an empty /data/db volume (official mongo image convention: every *.js
// file dropped into /docker-entrypoint-initdb.d/ is executed once, in
// filename order, via `mongosh`). It will NOT run again on later
// `docker compose up` calls once the volume exists — so this script is
// also kept safe to run BY HAND (e.g. `docker exec -i mongodb mongosh
// < 01-init-chat.js`) for local debugging, which is why every step below
// guards itself instead of assuming a clean slate.
//
// Pattern: Infrastructure as Code — the schema for chat_db/ChatServiceDb
// lives here as a versioned file in the repo, not as manual clicks in
// Compass or an undocumented one-off shell command. Anyone can rebuild
// this database from nothing by running `docker compose up`.
//
// Pattern: Database per Service — this script is the ONLY place that
// defines chat data's shape. The Chat Service (.NET) is the only service
// permitted to read/write these collections; no other service reaches
// into this database directly.
// =====================================================================

// Naming note: the T007 ticket text describes this database as "chat_db",
// but T004 already wired chat-service's actual connection string to
// ChatServiceDb (see docker-compose.override.yml:
// ConnectionStrings__ChatDb=mongodb://mongodb:27017/ChatServiceDb, and the
// same name in root .env.example's CHAT_DB_CONNECTION). Database-per-service
// only holds together if the schema we provision here and the database the
// running service actually opens are the SAME one — so this script follows
// the connection string that already exists rather than the ticket's
// literal name. (Also matches the {Service}ServiceDb convention used for
// every SQL Server database in T006.)
const chatDb = db.getSiblingDB('ChatServiceDb');

// Idempotency: TTL expiry is a business rule (privacy hard requirement:
// "no message history after chat closes"), not a constant — so it must be
// changeable via env var without editing code. mongosh, run by the mongo
// image's entrypoint, is itself a Node.js process, so it inherits whatever
// environment variables Docker Compose passed to this container. That's
// what makes `process.env.MONGO_TTL_SECONDS` readable here directly,
// without any extra config-loading step.
const ttlSeconds = parseInt(process.env.MONGO_TTL_SECONDS, 10) || 7200; // 2h default

// Idempotency guard: createCollection() throws NamespaceExists if the
// collection is already there. Checking first (rather than a try/catch
// that swallows the error) keeps the intent readable — "create only if
// missing" — and matches the ticket's explicit "do not drop collections
// on re-init" instruction.
function ensureCollection(database, name) {
  const alreadyExists = database.getCollectionNames().indexOf(name) !== -1;
  if (!alreadyExists) {
    database.createCollection(name);
  }
}

ensureCollection(chatDb, 'messages');
ensureCollection(chatDb, 'chat_rooms');

// Pattern: TTL Index (Automatic Lifecycle Management). Enforcing "a message
// expires N seconds after being sent" as a database-level guarantee — not
// an application cron job — means the guarantee holds even if the Chat
// Service crashes, is scaled to zero, or a future bug forgets to delete
// something. This is what turns "chat is ephemeral" from a promise the
// application code tries to keep into one MongoDB itself enforces. It is
// also WHY there is deliberately no message-history endpoint anywhere in
// this system: the data simply won't exist to serve.
//
// Idempotency wrinkle specific to TTL indexes: calling createIndex() again
// with THE SAME expireAfterSeconds is a harmless no-op. But if
// MONGO_TTL_SECONDS changes between runs, MongoDB refuses to silently
// change expireAfterSeconds on an existing index (throws
// IndexOptionsConflict) — so this needs an explicit "does it already match,
// and if not, drop and recreate" check, rather than a bare createIndex()
// call.
function ensureTtlIndex(collection, field, seconds) {
  const existing = collection.getIndexes().find(function (ix) {
    return ix.key && ix.key[field] === 1 && ix.expireAfterSeconds !== undefined;
  });

  if (existing && existing.expireAfterSeconds === seconds) {
    return; // already correct — nothing to do, safe re-run
  }
  if (existing) {
    collection.dropIndex(existing.name);
  }
  collection.createIndex({ [field]: 1 }, { expireAfterSeconds: seconds });
}

ensureTtlIndex(chatDb.messages, 'sent_at', ttlSeconds);

// Query-shape indexes. createIndex() is naturally idempotent when the
// definition is unchanged — MongoDB just confirms the index already
// matches and returns, no error — so no extra guard is needed here the way
// it was for the TTL index above.
//
// Two SEPARATE single-field indexes on `messages` (not one compound index)
// because Chat Service has two independent access patterns to serve:
//   1. "all messages in this chat room" -> filter by chat_id
//   2. "all messages sent by this user" -> filter by sender_id (used by
//      moderation / report lookups, not by the normal chat read path)
// A single compound (chat_id, sender_id) index would only efficiently serve
// queries that filter on chat_id first — it wouldn't help a sender_id-only
// scan, so two indexes are the right shape here, not one.
chatDb.messages.createIndex({ chat_id: 1 });
chatDb.messages.createIndex({ sender_id: 1 });

// chat_rooms is looked up by the connection it belongs to (Connection
// Service's `connection.accepted` event carries a connection_id — Chat
// Service uses this index to find or create the matching room).
chatDb.chat_rooms.createIndex({ connection_id: 1 });

print('[mongo-init] ChatServiceDb ready — messages (TTL ' + ttlSeconds + 's), chat_rooms initialized.');
