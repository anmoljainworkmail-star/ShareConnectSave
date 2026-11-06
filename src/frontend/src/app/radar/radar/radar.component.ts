import { Component, OnDestroy, OnInit } from '@angular/core';
import { Observable, Subject, Subscription, interval, of } from 'rxjs';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  startWith,
  switchMap,
  takeUntil
} from 'rxjs/operators';

import { ErrorEnvelope } from '../../auth/models/auth.models';
import { UserProfileService } from '../../profile-setup/user-profile.service';
import { DiscoveredUserCardDto, ScanMode, ScanStartResponse } from '../models/radar.models';
import { DestinationSearchService, DestinationSuggestion } from '../destination-search.service';
import { RadarDiscoveryService } from '../radar-discovery.service';

// A real search needs at least this many characters before it's worth a
// network round trip -- "h" or "hy" matches too much of Nominatim's whole
// planet-wide place database to be a useful ranked list, and querying on
// every single keystroke from character 1 would blow through the ~1 req/sec
// usage-policy budget DestinationSearchService's header comment flags.
const MIN_QUERY_LENGTH = 2;
const QUERY_DEBOUNCE_MS = 400;

type RadarView = 'idle' | 'scanning';
type ScanPresentationState = 'searching' | 'results' | 'empty';

// Narrow local shape for the one slice of the real DOM GeolocationPositionError
// this component actually reads (code/message) -- a real GeolocationPositionError
// is structurally assignable to this (it's a superset), but asserting a plain
// object literal (the "geolocation unsupported" case below) straight to the
// DOM type itself would fail TypeScript's object-literal overlap check, since
// that interface also requires PERMISSION_DENIED/POSITION_UNAVAILABLE/TIMEOUT
// constants this component has no use for.
interface LocationError {
  readonly code: number;
  readonly message: string;
}

// How often to re-fetch /discovery/scan/nearby while a scan is active.
// Chosen as roughly half of discovery-service's own Redis cache TTL
// (DiscoveryCacheService, 10s -- see T026) -- polling much faster than the
// cache's own freshness window would just replay the same cached response;
// polling much slower would make the radar visibly stale.
const NEARBY_POLL_INTERVAL_MS = 5000;

// FIGMA_SPEC.md Screen 06's quick departure-time choices -- a full date/time
// picker is not part of this ticket's scope. (Real destination search now
// exists via DestinationSearchService; see startScan()'s comment on the one
// remaining destination_lat/destination_lng simplification.)
const DEPARTURE_OFFSET_OPTIONS: ReadonlyArray<{ minutes: number; label: string }> = [
  { minutes: 0, label: 'Now' },
  { minutes: 5, label: 'In 5 min' },
  { minutes: 10, label: 'In 10 min' },
  { minutes: 15, label: 'In 15 min' },
  { minutes: 30, label: 'In 30 min' }
];

// Single Responsibility (S): this component owns the scan's own lifecycle
// (idle -> scanning -> idle) and the sweep/dot rendering that goes with it.
// It does NOT own "what a discovered user's card looks like" (UserCardComponent's
// job) or "how to fetch a photo" (UserProfileService.resolvePhotoUrl's job) --
// see the ticket's "Patterns demonstrated" section for the full breakdown.
@Component({
  selector: 'app-radar',
  templateUrl: './radar.component.html',
  styleUrl: './radar.component.css',
  // Angular 19 defaults new components to standalone: true. This project is
  // module-based only (no standalone components, per project-wide Angular
  // rule), so that default must be overridden explicitly on every
  // hand-written component.
  standalone: false
})
export class RadarComponent implements OnInit, OnDestroy {
  readonly departureOptions = DEPARTURE_OFFSET_OPTIONS;

  view: RadarView = 'idle';

  // Idle-state form fields (Screen 06).
  destinationLabel = '';
  departureOffsetMinutes = 0;
  isStartingScan = false;
  startError: string | null = null;

  // Google Maps/Rapido-style destination autocomplete, backed by a REAL
  // place search (DestinationSearchService -> OpenStreetMap Nominatim, see
  // that service's header comment for why Nominatim rather than Google
  // Places). `selectedDestination` is only set when the user actually picks
  // a suggestion (including "Use current location"); free-typing without
  // picking one leaves it null, and startScan() falls back to the existing
  // current-position simplification in that case.
  destinationSuggestions: DestinationSuggestion[] = [];
  showDestinationSuggestions = false;
  isSearchingDestinations = false;
  isResolvingCurrentLocation = false;
  private selectedDestination: DestinationSuggestion | null = null;
  // Keystrokes feed this Subject rather than calling the search service
  // directly from onDestinationInput() -- piping it through
  // debounceTime/distinctUntilChanged/switchMap below is what turns "one
  // HTTP call per keystroke" into "one call per pause in typing, and any
  // still-in-flight call for a now-stale query gets cancelled," which is
  // the whole reason a real, rate-limited search needs an RxJS pipeline
  // instead of the old synchronous array filter.
  private readonly destinationQuery$ = new Subject<string>();
  // Guards the (blur) handler below: a plain (blur) fires BEFORE a
  // suggestion's (click) does, so hiding the dropdown on blur would close it
  // out from under the very click meant to select something. Delaying the
  // hide by one tick (mousedown, not click, so it still beats blur) lets
  // that click land first -- the standard pattern for a dismiss-on-blur
  // dropdown that must also support click-to-select.
  private blurTimeoutId: ReturnType<typeof setTimeout> | null = null;

  // Mode badge (AC: "Mode badge switches correctly") -- driven purely by
  // browser connectivity, not a server-reported value. REQUIREMENTS.md §4:
  // BLE is the offline fallback that activates automatically when the
  // device loses internet connectivity during an active scan. Full BLE
  // scanning (Web Bluetooth) is T065's scope -- this ticket only owns the
  // badge switching, not the actual Bluetooth candidate source.
  mode: ScanMode = 'GPS';

  discoveredUsers: DiscoveredUserCardDto[] = [];
  selectedUser: DiscoveredUserCardDto | null = null;
  private hasReceivedFirstNearbyResponse = false;

  private sessionId: number | null = null;
  private pollSubscription?: Subscription;
  private locationWatchId: number | null = null;

  // Teardown signal for long-lived chains that can still be in flight when
  // the component is destroyed (e.g. navigating away mid-startScan, before
  // `view` ever flips to 'scanning' and teardownScan()'s own cleanup gets a
  // chance to run). Piping a chain through takeUntil(this.destroy$) and
  // firing this Subject once in ngOnDestroy unsubscribes it unconditionally
  // -- unlike pollSubscription's manual Subscription handle, this covers
  // subscriptions that never got far enough to be stored anywhere.
  private readonly destroy$ = new Subject<void>();

  // Bound once so removeEventListener in ngOnDestroy actually matches the
  // listener addEventListener registered in ngOnInit -- an inline arrow
  // passed to each call would be a different function reference each time.
  private readonly handleOnline = (): void => {
    this.mode = 'GPS';
  };
  private readonly handleOffline = (): void => {
    this.mode = 'BLE';
  };

  constructor(
    private readonly radarDiscoveryService: RadarDiscoveryService,
    private readonly userProfileService: UserProfileService,
    private readonly destinationSearchService: DestinationSearchService
  ) {}

  ngOnInit(): void {
    this.mode = navigator.onLine ? 'GPS' : 'BLE';
    // 'online'/'offline' are standard DOM events, patched by zone.js the
    // same way every other addEventListener call is (unlike LoginComponent's
    // GSI callback, which fires from a plain <script> tag Angular never
    // instrumented and therefore needs an explicit NgZone.run) -- so no
    // NgZone wrapping is needed here for change detection to pick this up.
    window.addEventListener('online', this.handleOnline);
    window.addEventListener('offline', this.handleOffline);

    this.destinationQuery$
      .pipe(
        debounceTime(QUERY_DEBOUNCE_MS),
        distinctUntilChanged(),
        switchMap(query => {
          const trimmed = query.trim();
          if (trimmed.length < MIN_QUERY_LENGTH) {
            this.isSearchingDestinations = false;
            return of<DestinationSuggestion[]>([]);
          }
          this.isSearchingDestinations = true;
          return this.destinationSearchService.search(trimmed).pipe(
            // A failed search (offline, Nominatim rate-limited/unreachable)
            // degrades to "no results" rather than surfacing an error inside
            // the dropdown -- the user can still fall back to "Use current
            // location" or just type a free-text label with no coordinates.
            catchError(() => of<DestinationSuggestion[]>([]))
          );
        }),
        takeUntil(this.destroy$)
      )
      .subscribe(results => {
        this.isSearchingDestinations = false;
        this.destinationSuggestions = results;
      });
  }

  ngOnDestroy(): void {
    window.removeEventListener('online', this.handleOnline);
    window.removeEventListener('offline', this.handleOffline);

    if (this.blurTimeoutId !== null) {
      clearTimeout(this.blurTimeoutId);
      this.blurTimeoutId = null;
    }

    this.destinationQuery$.complete();

    // Unconditional, in addition to (not instead of) teardownScan() below:
    // this cancels any subscription piped through takeUntil(this.destroy$),
    // including a startScan() chain still in flight (geolocation prompt
    // pending, or the scan/start|scan/location|users/me round trip still in
    // the air) when the user navigates away before `view` ever reaches
    // 'scanning' -- the one case the view === 'scanning' guard below cannot
    // see.
    this.destroy$.next();
    this.destroy$.complete();

    // Closing the scan screen (navigating away mid-scan) must flip status
    // back to "unavailable" the same as an explicit Stop Scan tap -- see the
    // ticket's own framing: "Discovery Service will keep matching someone
    // who closed the app and walked away" otherwise. Known, accepted gap: a
    // hard tab/browser close fires neither this hook nor a reliable
    // synchronous network call (`beforeunload` cannot await an Observable),
    // so that specific exit path is not covered here.
    if (this.view === 'scanning') {
      this.teardownScan();
    }
  }

  get scanState(): ScanPresentationState {
    // Guard clause: before the first /scan/nearby response ever lands, this
    // is "searching", regardless of how many (zero) users are in the array --
    // FIGMA_SPEC.md Screen 07 distinguishes "still looking" from "looked and
    // found nobody".
    if (!this.hasReceivedFirstNearbyResponse) {
      return 'searching';
    }
    return this.discoveredUsers.length > 0 ? 'results' : 'empty';
  }

  get canStartScan(): boolean {
    return this.destinationLabel.trim().length > 0 && !this.isStartingScan;
  }

  // (input) handler on the destination field -- pushes the raw keystroke
  // into destinationQuery$ (see that Subject's own comment for why a real
  // search needs the debounce/cancel pipeline instead of filtering
  // in-place) and invalidates any previously picked suggestion, since
  // free-typing after a pick means the field no longer matches what was
  // selected.
  onDestinationInput(): void {
    this.selectedDestination = null;
    this.showDestinationSuggestions = true;
    this.destinationQuery$.next(this.destinationLabel);
  }

  onDestinationFocus(): void {
    if (this.blurTimeoutId !== null) {
      clearTimeout(this.blurTimeoutId);
      this.blurTimeoutId = null;
    }
    this.showDestinationSuggestions = true;
    // Re-run the search for whatever is already typed -- covers
    // re-focusing a field that still has text but whose dropdown was
    // dismissed (e.g. blurred, then clicked back into) without a result
    // list already in hand.
    if (this.destinationLabel.trim().length >= MIN_QUERY_LENGTH && this.destinationSuggestions.length === 0) {
      this.destinationQuery$.next(this.destinationLabel);
    }
  }

  onDestinationBlur(): void {
    // See blurTimeoutId's own comment -- this delay is what lets a
    // suggestion's (mousedown) run before the dropdown disappears.
    this.blurTimeoutId = setTimeout(() => {
      this.showDestinationSuggestions = false;
      this.blurTimeoutId = null;
    }, 150);
  }

  selectSuggestion(suggestion: DestinationSuggestion): void {
    this.selectedDestination = suggestion;
    this.destinationLabel = suggestion.label;
    this.showDestinationSuggestions = false;
  }

  // Top-of-list "Use current location" row (Google Maps/Rapido convention) --
  // reuses getCurrentPosition() rather than a second geolocation call path,
  // then reverse-geocodes the fix into a real place label via the same
  // DestinationSearchService the text search uses, instead of a flat
  // "Current location" string.
  useCurrentLocationAsDestination(): void {
    this.isResolvingCurrentLocation = true;
    this.getCurrentPosition()
      .pipe(
        switchMap(position =>
          this.destinationSearchService.reverseGeocode(position.coords.latitude, position.coords.longitude).pipe(
            // Reverse geocoding is cosmetic (the label shown), not load-bearing
            // (the coordinates sent onward) -- a failed lookup still carries
            // the real GPS fix forward under a plain fallback label instead of
            // blocking "Use current location" entirely.
            catchError(() =>
              of<DestinationSuggestion>({
                label: 'Current location',
                subLabel: 'Using your device location',
                lat: position.coords.latitude,
                lng: position.coords.longitude
              })
            )
          )
        ),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: suggestion => {
          this.isResolvingCurrentLocation = false;
          this.selectedDestination = suggestion;
          this.destinationLabel = suggestion.label;
          this.showDestinationSuggestions = false;
        },
        error: () => {
          this.isResolvingCurrentLocation = false;
          this.startError = 'Location permission is required to use your current location.';
        }
      });
  }

  startScan(): void {
    // Guard clause: mirrors Screen 06's own disabled-button state so a
    // programmatic call (or a stale click queued before re-render) can't
    // bypass it.
    if (!this.canStartScan) {
      return;
    }

    this.isStartingScan = true;
    this.startError = null;

    this.getCurrentPosition()
      .pipe(
        switchMap(position =>
          this.radarDiscoveryService
            .startScan({
              // Known simplification (flagged, not hidden): ScanStartRequest's
              // destination_lat/destination_lng are meant to be a GEOCODED
              // destination (Screen 06: "Destination input with map pin icon").
              // DestinationSearchService (real Nominatim search, see its own
              // header comment) now supplies a real lat/lng whenever the user
              // actually picks a suggestion -- this only falls back to the
              // caller's CURRENT position when they typed a destination and
              // never picked a suggestion for it, so Start Scan stays
              // genuinely end-to-end functional against the real
              // discovery-service either way. See .claude/notes/follow-ups.md
              // "From T064" for the tracked follow-up to replace Nominatim
              // with a real Google Places Autocomplete call, per this
              // project's stated tech stack.
              destination_lat: this.selectedDestination?.lat ?? position.coords.latitude,
              destination_lng: this.selectedDestination?.lng ?? position.coords.longitude,
              destination_label: this.destinationLabel.trim(),
              departure_time: this.computeDepartureIso(),
              // T066 (Discovery Filters Panel) owns the UI control for this --
              // always false here until that ticket adds it, rather than this
              // ticket inventing a toggle for a screen it doesn't build.
              women_only: false
            })
            // Carries `position` forward alongside the response -- the very
            // next step needs it again (see the PUT /scan/location call
            // below), and re-calling getCurrentPosition a second time would
            // just be a second permission-prompt-adjacent round trip for data
            // this step already has in hand.
            .pipe(map((scanResponse: ScanStartResponse) => ({ position, scanResponse })))
        ),
        switchMap(({ position, scanResponse }) => {
          this.sessionId = scanResponse.session_id;
          // Confirmed by reading ScanSessionServiceImpl.startScan directly:
          // it persists destination_lat/lng to SQL but writes NO
          // scan:{id}:location Redis key -- that key only exists once
          // PUT /scan/location is called at least once. ScanQueryServiceImpl
          // .findNearby's own "What NOT to do" guard treats a missing caller
          // location as "return an empty result" rather than an error (see
          // its getSessionLocation null-check), so skipping this call
          // wouldn't surface as a visible failure anywhere -- the radar would
          // just silently show "No one nearby" forever. Seeding it here with
          // the same position just used to start the scan closes that gap
          // before the first /scan/nearby poll ever fires.
          return this.radarDiscoveryService.updateLocation({
            lat: position.coords.latitude,
            lng: position.coords.longitude
          });
        }),
        switchMap(() => {
          // Shared status-write path (CLAUDE.md pattern: "Single source of
          // truth for state") -- the exact same PATCH /users/me call
          // SettingsComponent's status toggle (T070) will use. Written AFTER
          // scan/start (and its location seed) succeeds, not before:
          // flipping status to "looking" before confirming a real,
          // query-able session exists would leave the user marked "looking"
          // with no actual active scan if an earlier step had failed.
          return this.userProfileService.updateProfile({ status: 'looking' });
        }),
        // Subject + takeUntil: the standard RxJS unsubscribe-on-destroy
        // pattern -- emitting on destroy$ (ngOnDestroy) tears this whole
        // chain down even if it is still mid-flight, instead of leaving a
        // completed-too-late subscription free to keep running (and
        // eventually flip status to "looking" for a user who has already
        // navigated away).
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: () => {
          this.isStartingScan = false;
          this.view = 'scanning';
          this.hasReceivedFirstNearbyResponse = false;
          this.beginWatchingLocation();
          this.beginPolling();
        },
        error: (error: ErrorEnvelope | LocationError) => {
          this.isStartingScan = false;
          this.startError = this.isGeolocationError(error)
            ? 'Location permission is required to start a scan.'
            : error.message;
        }
      });
  }

  stopScan(): void {
    this.teardownScan();
  }

  selectUser(user: DiscoveredUserCardDto): void {
    this.selectedUser = user;
  }

  closeUserCard(): void {
    this.selectedUser = null;
  }

  // Dot placement on the SVG/CSS radar stage. The one real, backend-provided
  // number is distance_km -- it drives radial position. There is no bearing
  // (direction) field anywhere on UserCardResponse's wire contract (confirmed
  // by reading discovery-service's source: the actual bearing/route-overlap
  // math stays server-side inside ScanQueryServiceImpl and is never returned
  // to the client), so the ANGLE below is a stable, id-derived placement for
  // visual spacing only -- it does not claim to represent this person's true
  // compass bearing from the caller. See radar.models.ts's
  // DiscoveredUserCardDto comment and .claude/notes/follow-ups.md "From
  // T064" for the tracked gap.
  // Positions are PERCENTAGES of the stage's own box, not fixed pixels --
  // radar-shared.css's .radar-stage now sizes itself responsively
  // (--radar-stage-size: clamp(..., vmin, ...)) instead of a fixed 320px, so
  // a dot placed in absolute px would land in the wrong spot (or off the
  // edge entirely) on any viewport that resolves to a different stage size.
  // Percentages scale automatically with whatever size the stage actually
  // renders at, the same reasoning radar-shared.css's own ring/label/pulse
  // rules now follow.
  dotPosition(user: DiscoveredUserCardDto): { left: string; top: string } {
    const center = 50;
    // Golden-angle increment keeps consecutively-id'd users visually spread
    // around the circle instead of clustering, without needing real bearing
    // data to justify any particular angle.
    const angleDegrees = (user.id * 137.508) % 360;
    const angleRadians = (angleDegrees * Math.PI) / 180;
    // 45px and 160px out of the old fixed 320px stage, as percentages --
    // ring 1 (0.5km label) sits at 85px/320px radius, ring 3 (1.5km label)
    // at 160px/320px -- see radar-shared.css's .radar-ring-label offsets.
    // Linearly mapping the real distance_km onto that same span keeps a
    // closer match visually closer, which IS a real, backend-given fact,
    // unlike the angle above.
    const minRadiusPct = (45 / 320) * 100;
    const maxRadiusPct = (160 / 320) * 100;
    const maxDisplayDistanceKm = 1.5;
    const clampedDistanceKm = Math.min(user.distance_km, maxDisplayDistanceKm);
    const radiusPct = minRadiusPct + (clampedDistanceKm / maxDisplayDistanceKm) * (maxRadiusPct - minRadiusPct);

    const left = center + radiusPct * Math.cos(angleRadians);
    const top = center + radiusPct * Math.sin(angleRadians);
    return { left: `${left}%`, top: `${top}%` };
  }

  trackByUserId(_index: number, user: DiscoveredUserCardDto): number {
    return user.id;
  }

  initials(name: string): string {
    return name.charAt(0).toUpperCase();
  }

  // REQUIREMENTS.md §3: "Location is tracked continuously while scan is
  // active." watchPosition (not a second interval timer re-calling
  // getCurrentPosition) is the correct primitive here -- it only fires when
  // the browser actually has a new fix, instead of polling GPS hardware on a
  // fixed clock regardless of whether the device has even moved.
  private beginWatchingLocation(): void {
    if (!navigator.geolocation) {
      return;
    }
    this.locationWatchId = navigator.geolocation.watchPosition(
      position => {
        // Best-effort, same reasoning as teardownScan's cleanup calls below:
        // one missed location update shouldn't surface as a user-visible
        // error, the next watchPosition tick will simply try again.
        this.radarDiscoveryService
          .updateLocation({ lat: position.coords.latitude, lng: position.coords.longitude })
          .subscribe({ error: () => undefined });
      },
      () => undefined,
      { enableHighAccuracy: false, maximumAge: 10_000, timeout: 15_000 }
    );
  }

  private stopWatchingLocation(): void {
    if (this.locationWatchId === null) {
      return;
    }
    navigator.geolocation.clearWatch(this.locationWatchId);
    this.locationWatchId = null;
  }

  private beginPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = interval(NEARBY_POLL_INTERVAL_MS)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.radarDiscoveryService.getNearby().pipe(
            // An inner observable erroring inside switchMap is terminal for
            // the WHOLE outer chain, including the interval() that drives
            // every future tick -- catching here, inside the projection,
            // means only THIS tick is treated as a no-op (keep showing the
            // last known list); the next interval tick still fires.
            // Catching only in the outer subscribe's error handler (the
            // previous bug) would silently and permanently freeze polling
            // on the very first /scan/nearby failure, most likely exactly
            // during an offline/online transition.
            catchError(() => of(this.discoveredUsers))
          )
        )
      )
      .subscribe({
        next: users => {
          this.hasReceivedFirstNearbyResponse = true;
          this.discoveredUsers = users;
        }
      });
  }

  private teardownScan(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = undefined;
    this.stopWatchingLocation();

    const hadActiveSession = this.sessionId !== null;
    this.sessionId = null;
    this.view = 'idle';
    this.discoveredUsers = [];
    this.selectedUser = null;
    this.hasReceivedFirstNearbyResponse = false;

    // Guard clause: nothing server-side to tear down if a session was never
    // actually created (e.g. this is called from ngOnDestroy before
    // startScan ever succeeded).
    if (!hadActiveSession) {
      return;
    }

    // Best-effort cleanup: the UI has already switched back to idle above
    // regardless of whether these two calls succeed -- a user closing the
    // scan screen shouldn't be blocked on, or see an error from, cleanup
    // calls they didn't directly ask to confirm. Each Observable is still
    // subscribed (RxJS cold observables do nothing otherwise), just with an
    // error handler that intentionally swallows the failure.
    this.radarDiscoveryService.stopScan().subscribe({ error: () => undefined });
    this.userProfileService.updateProfile({ status: 'unavailable' }).subscribe({ error: () => undefined });
  }

  private computeDepartureIso(): string {
    return new Date(Date.now() + this.departureOffsetMinutes * 60_000).toISOString();
  }

  // Wraps the callback-based Geolocation API in an Observable so it composes
  // with the same switchMap/catchError pipeline as every HTTP call in this
  // codebase, instead of mixing Promise/await into an otherwise all-RxJS
  // component.
  private getCurrentPosition(): Observable<GeolocationPosition> {
    return new Observable<GeolocationPosition>(subscriber => {
      if (!navigator.geolocation) {
        const unsupportedError: LocationError = { code: 0, message: 'Geolocation is not supported on this device.' };
        subscriber.error(unsupportedError);
        return;
      }
      navigator.geolocation.getCurrentPosition(
        position => {
          subscriber.next(position);
          subscriber.complete();
        },
        error => subscriber.error(error)
      );
    });
  }

  private isGeolocationError(error: unknown): error is LocationError {
    return typeof error === 'object' && error !== null && 'code' in error && 'message' in error && !('traceId' in error);
  }
}
