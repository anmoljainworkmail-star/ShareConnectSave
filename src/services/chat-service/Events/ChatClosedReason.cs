namespace chat_service.Events;

// Mirrors contracts/kafka/chat.closed.schema.json's `closed_reason` enum
// exactly - that schema file (checked into the repo, validated by
// /validate-contracts) is the authoritative wire contract for this event,
// not this ticket's own prose. The schema's own comment explains why this is
// an enum, not free text: "restricts the state machine to known transitions
// - a service cannot accidentally send an unrecognised reason that a
// consumer silently ignores."
//
// T037 only ever produces the first two values - MetSuccessfully (both users
// confirmed, grace timer elapsed) and Timeout (2-hour auto-close poll).
// AdminClosed is reserved for a future Admin Service action; defining it here
// now (rather than only adding it when that ticket lands) costs nothing and
// keeps this file a complete mirror of the schema's enum, so nobody has to
// cross-reference the JSON file to know what's legal here.
public static class ChatClosedReason
{
    public const string MetSuccessfully = "met_successfully";
    public const string Timeout = "timeout";
    public const string AdminClosed = "admin_closed";
}
