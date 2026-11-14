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
  // T066 (Discovery Filters Panel). Known backend contract gap (flagged, not
  // hidden): discovery-service's ScanStartRequest.java has no field for
  // either of these today -- radius and departure window are still read only
  // from DiscoveryProperties (discovery.radius.km / departure-window-minutes
  // in application.yml). Spring Boot's default Jackson config ignores
  // unknown JSON properties, so sending them is safe and forward-compatible:
  // the day a backend ticket adds the fields, no Angular-side change is
  // needed. Until then the server keeps applying its own defaults.
  radius_km?: number;
  departure_window_minutes?: number;
}

// T066: the three user-adjustable discovery filters. Plain data, no behaviour
// -- ScanSessionService owns where it lives, DiscoveryFiltersPanelComponent
// only edits a draft copy of it.
export type DepartureWindowMinutes = 15 | 30 | 60;

export interface DiscoveryFilters {
  radiusKm: number;
  departureWindowMinutes: DepartureWindowMinutes;
  womenOnly: boolean;
}

// REQUIREMENTS.md section 4 defaults (1 km radius, +/-30 min departure
// window), mirroring discovery-service's own DISCOVERY_RADIUS_KM /
// DISCOVERY_DEPARTURE_WINDOW_MINUTES defaults so an untouched filter panel
// and the server's own fallback agree. Frozen so no caller can mutate the
// shared default object by accident.
export const DEFAULT_DISCOVERY_FILTERS: Readonly<DiscoveryFilters> = Object.freeze({
  radiusKm: 1,
  departureWindowMinutes: 30,
  womenOnly: false
});

// FIGMA_SPEC.md Screen 17: slider range 0.5 - 5 km.
export const MIN_RADIUS_KM = 0.5;
export const MAX_RADIUS_KM = 5;
export const RADIUS_STEP_KM = 0.5;

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
  // Compass direction from the caller to this person (0 = north, clockwise),
  // added by discovery-service alongside distance_km so the street map can
  // stand the avatar where the person really is. Optional/nullable: absent on
  // an older backend and for BLE or degraded cards -- consumers must fall back.
  bearing_deg?: number | null;
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

// Interface Segregation (I): UserCardComponent only DISPLAYS a person -- it
// never needs distance (discovery-only) or the unavailable flag unless the
// caller has them. T067's InboundRequestComponent shows the SENDER of a
// request, whose card comes from User Service's public profile
// (id/name/photo_url/identity_badge) and has no distance at all. Typing the
// card's input as this narrower shape lets that caller pass an honest object
// instead of inventing distance_km: 0 to satisfy a type built for radar dots.
// A full DiscoveredUserCardDto is assignable to this, so radar callers are
// unchanged.
export type UserCardData = Omit<DiscoveredUserCardDto, 'distance_km' | 'unavailable'> &
  Partial<Pick<DiscoveredUserCardDto, 'distance_km' | 'unavailable'>>;
