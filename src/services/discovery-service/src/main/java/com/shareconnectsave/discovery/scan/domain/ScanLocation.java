package com.shareconnectsave.discovery.scan.domain;

// Pattern: this is the Redis-only value ScanSessionService writes to
// `scan:{session_id}:location` — it never touches JPA/Hibernate and has no
// corresponding SQL column, by design (Location Privacy: a live GPS fix is
// never persisted to SQL, only ever held in Redis with a short TTL). Kept as
// its own named type (not a raw "lat,lng" string) so T023's read side gets a
// stable, self-describing shape back out of Redis instead of having to parse
// a delimited string.
public record ScanLocation(double lat, double lng) {
}
