// Wire DTOs for discovery-service's /scan/* endpoints (reached through the
// gateway at `${environment.apiBase}/discovery/scan/*` -- see api-gateway's
// appsettings.json "discovery-route", PathRemovePrefix "/discovery"), named
// and cased (snake_case) to match discovery-service's Java records exactly,
// the same discipline auth/models/auth.models.ts and
// profile-setup/models/profile-setup.models.ts already follow for their own
// services' DTOs.

// Interface Segregation (I): this is its own small contract file for the
// discovery/radar domain, separate from auth's and profile's models -- a
// consumer of radar DTOs never has to pull in GoogleAuthApiResponse or
// UserProfileDto, and vice versa.

export type ScanMode = 'GPS' | 'BLE';

// Mirrors discovery-service's ScanStartRequest.java field-for-field.
export interface ScanStartRequest {
  destination_lat: number;
  destination_lng: number;
  destination_label?: string;
  departure_time?: string;
  women_only?: boolean;
}

// Mirrors discovery-service's ScanStartResponse.java. `mode` always comes
// back "gps" today (ScanSessionServiceImpl hardcodes it) -- BLE is a
// client-only fallback this ticket drives from navigator.onLine, not a
// server-reported scan mode (see RadarComponent's handleOnline/handleOffline).
export interface ScanStartResponse {
  session_id: number;
  mode: string;
}

// Mirrors discovery-service's ScanLocationRequest.java -- PUT /scan/location
// body. No session_id field: the active session is resolved server-side from
// the X-User-Id header the gateway already injects.
export interface ScanLocationRequest {
  lat: number;
  lng: number;
}

// Mirrors discovery-service's UserCardResponse.java EXACTLY for the first six
// fields -- confirmed by reading that record directly, not guessed. The
// fields below that are marked optional are NOT part of today's wire
// contract; see this interface's own trailing comment block for why they
// exist here anyway.
export interface DiscoveredUserCardDto {
  id: number;
  name: string;
  photo_url: string | null;
  identity_badge: boolean;
  distance_km: number;
  // Circuit Breaker (Decorator around UserServiceClient, see CLAUDE.md's GoF
  // table) degraded-response marker: true only for the placeholder card
  // DiscoveryCacheService manufactures when User Service's breaker is open.
  unavailable: boolean;

  // --- Known backend contract gap (flagged, not hidden) -----------------
  // FIGMA_SPEC.md SCREEN 09 and this ticket's own spec both require a
  // rating, destination, "leaving in X min", route-match %, and preferred
  // language on every discovered-user card. discovery-service's
  // UserCardResponse.java does not return any of these today -- confirmed
  // by reading the record's source directly, not assumed. Two different
  // reasons, worth distinguishing:
  //   - rating/trusted: UserCardResponse's own doc comment explains this was
  //     deliberately omitted because Rating Service "does not exist in this
  //     codebase yet" -- but Rating Service (Phase 7, T041-T046) has since
  //     shipped and that comment was never revisited. This is now a stale
  //     comment describing a gap that could be closed.
  //   - destination_label/leaving_in_minutes/route_match_percent/
  //     preferred_language: these were never part of UserCardResponse at
  //     all, even though ScanStartRequest carries a caller's OWN destination/
  //     departure_time -- nothing propagates the MATCHED candidate's
  //     equivalent values back out.
  // Per this project's "no fabricated data for a feature that doesn't exist
  // server-side" convention (same rule UserCardResponse's own comment
  // invokes), T064 does not invent values for these fields. They are typed
  // as optional so UserCardComponent can render them the day a backend
  // ticket adds them -- with zero Angular-side contract change -- and so the
  // template can cleanly *ngIf away a row this payload doesn't have instead
  // of printing "undefined". Tracked in .claude/notes/follow-ups.md "From
  // T064".
  rating_average?: number;
  rating_count?: number;
  trusted?: boolean;
  destination_label?: string;
  leaving_in_minutes?: number;
  route_match_percent?: number;
  preferred_language?: string;
}
