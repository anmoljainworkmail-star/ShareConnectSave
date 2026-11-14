import {
  AfterViewInit,
  ApplicationRef,
  Component,
  ComponentRef,
  ElementRef,
  EnvironmentInjector,
  NgZone,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation,
  createComponent
} from '@angular/core';
import { Router } from '@angular/router';
import type { GeoJSONSource, Map as MapLibreMap, Marker as MapLibreMarker } from 'maplibre-gl';
import { Observable, Subject, Subscription, interval, of } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, map, startWith, switchMap, takeUntil } from 'rxjs/operators';

import { environment } from '../../../environments/environment';
import { ErrorEnvelope } from '../../auth/models/auth.models';
import { ConnectionService } from '../../connections/connection.service';
import { UserProfileService } from '../../profile-setup/user-profile.service';
import { ToastService } from '../../shared/toast/toast.service';
import { DestinationSearchService, DestinationSuggestion } from '../destination-search.service';
import { DiscoveredUserCardDto, ScanMode, ScanStartResponse } from '../models/radar.models';
import { RadarDiscoveryService } from '../radar-discovery.service';
import { ScanSessionService } from '../scan-session.service';
import { UserAvatar3dComponent } from '../user-avatar-3d/user-avatar-3d.component';
import { ConnectionState } from '../user-card/user-card.component';
import { GeoPoint, Position, circleRing, offsetMeters, placeholderBearingRadians, wedgeRing } from './radar-geo';
import { applyVividStyle } from './radar-map-style';

type MapLibreModule = typeof import('maplibre-gl');
type MapPhase = 'loading' | 'ready';
type ScanPresentationState = 'searching' | 'results' | 'empty';

// Same narrow shape RadarComponent uses for the one slice of the DOM
// GeolocationPositionError this screen reads.
interface LocationError {
  readonly code: number;
  readonly message: string;
}

// Minimal GeoJSON shapes. Declared locally (instead of importing the GeoJSON
// ambient types) so this file compiles regardless of which @types packages the
// map library happens to pull in -- the literal `type` strings are what let
// these assign cleanly to the map's own GeoJSON parameter type.
interface LineFeature {
  type: 'Feature';
  properties: Record<string, string | number>;
  geometry: { type: 'LineString'; coordinates: Position[] };
}
interface PolygonFeature {
  type: 'Feature';
  properties: Record<string, string | number>;
  geometry: { type: 'Polygon'; coordinates: Position[][] };
}
interface FeatureCollectionOf<F> {
  type: 'FeatureCollection';
  features: F[];
}

const EMPTY_COLLECTION: FeatureCollectionOf<never> = { type: 'FeatureCollection', features: [] };

// Same cadence reasoning as RadarComponent: about half of discovery-service's
// 10 s Redis cache TTL, so polling is neither a replay of the cache nor stale.
const NEARBY_POLL_INTERVAL_MS = 5000;

// Camera: a steep tilt is what turns flat streets into a city of 3D blocks.
const DEFAULT_ZOOM = 15.8;
const DEFAULT_PITCH = 58;
const DEFAULT_BEARING = -17;
// How much of the bottom of the screen the sheet covers -- the map is padded by
// this much so "you" sits in the visible part instead of behind the sheet.
const SHEET_PADDING_PX = 150;

// A style that never loads (blocked host, offline, tile server down) must not
// leave the user on a blank screen forever.
const MAP_LOAD_TIMEOUT_MS = 15_000;

// Pattern: Heartbeat (liveness signal). Discovery-service keeps each scanner's
// position in Redis with a 30 s TTL and SKIPS any candidate whose key has
// expired ("No cached location for candidate session ..."). watchPosition only
// fires when the device MOVES, so a phone held still would fall silent, expire
// after 30 s, and vanish from everyone else's radar. Re-sending the last known
// fix every 10 s (a third of the TTL, so one dropped beat is survivable) keeps
// a stationary user visible. Without it, two phones on a desk never see each other.
const LOCATION_HEARTBEAT_MS = 10_000;

// Same destination-search tuning as RadarComponent.
const MIN_QUERY_LENGTH = 2;
const QUERY_DEBOUNCE_MS = 400;

// How long after the style loads we wait for at least one real map tile before
// telling the user the tile host is unreachable instead of showing a blank map.
const TILE_CHECK_DELAY_MS = 8000;

// Radar rings as fractions of the active radius. The OUTER ring is the real
// discovery radius, so the picture and the server's filter always agree.
const RING_FRACTIONS = [0.25, 0.5, 0.75, 1] as const;

// Sweep: stacked wedges with falling opacity. Vector fills cannot hold a
// gradient, so the "fading trail" is faked with slices -- the same trick film
// uses by blending frames to fake motion blur.
const SWEEP_PERIOD_MS = 2500;
const SWEEP_SLICES = 14;
const SWEEP_SPAN_RAD = 0.9;
const SWEEP_PEAK_OPACITY = 0.42;

// Single Responsibility (S): this component wires three things together --
// the map, the scan lifecycle, and the template. It does NOT own geometry
// (radar-geo.ts), colours (radar-map-style.ts), or how a person's card looks
// (UserCardComponent).
//
// Why it is its own route (/radar/v2) rather than a rewrite of RadarComponent:
// Strangler Fig (CLAUDE.md, "future"): build the replacement beside the old
// screen, let real use decide, then retire the old one. The classic radar is
// also the FALLBACK -- if the map cannot load, this screen hands the user to it.
//
// Known duplication (flagged, not hidden): the scan start/poll/stop lifecycle
// below mirrors RadarComponent's. If v2 is adopted, extract it into one
// ScanLifecycleService both screens inject, instead of keeping two copies.
//
// ViewEncapsulation.None: the map library creates its own DOM (markers,
// controls) outside Angular's templates, so Angular's per-component style
// scoping attributes never land on it and scoped CSS would not match. Every
// class here is prefixed "rm-" so un-scoped styles cannot leak into other
// screens.
@Component({
  selector: 'app-radar-map',
  templateUrl: './radar-map.component.html',
  styleUrl: './radar-map.component.css',
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class RadarMapComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('mapHost', { static: true }) private readonly mapHost!: ElementRef<HTMLDivElement>;

  mapPhase: MapPhase = 'loading';
  mode: ScanMode = 'GPS';
  isScanning = false;
  isStartingScan = false;
  startError: string | null = null;
  locationDenied = false;

  // True when the style loaded but no map tile ever arrived (host blocked by a
  // firewall/ad-blocker, tile server down). Drives a visible explanation
  // instead of a silent, blank map.
  tilesBlocked = false;

  // Destination (same idea as the classic radar's "Where are you heading?").
  // Discovery matches people by ROUTE OVERLAP: the bearing from you to your own
  // destination vs the bearing from you to the other person's destination.
  // With destination = "where I stand" the bearing is undefined, so who you
  // match becomes arbitrary -- a real, shared destination is required.
  destinationLabel = '';
  selectedDestination: DestinationSuggestion | null = null;
  destinationSuggestions: DestinationSuggestion[] = [];
  isSearchingDestinations = false;
  showDestinationSuggestions = false;
  private readonly destinationQuery$ = new Subject<string>();

  discoveredUsers: DiscoveredUserCardDto[] = [];
  selectedUser: DiscoveredUserCardDto | null = null;
  private hasReceivedFirstNearbyResponse = false;
  private readonly connectionStates = new Map<number, ConnectionState>();

  private maplibre?: MapLibreModule;
  private mapInstance?: MapLibreMap;
  private mapLoaded = false;
  private fellBack = false;
  // Set once ngOnDestroy runs, so an async step that resumes afterwards (the
  // map chunk finishing its download) can tell the screen is already gone.
  private destroyed = false;
  private followUser = true;
  private userPosition: GeoPoint | null = null;
  private meMarker?: MapLibreMarker;
  private readonly pinMarkers = new Map<number, MapLibreMarker>();
  // The Three.js figure living inside each pin, kept so it can be destroyed
  // (releasing its WebGL context) when the pin goes away.
  private readonly pinAvatars = new Map<number, ComponentRef<UserAvatar3dComponent>>();

  private sweepFrameId: number | null = null;
  private loadTimeoutId: number | null = null;
  private readonly prefersReducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

  private sessionId: number | null = null;
  private pollSubscription?: Subscription;
  private heartbeatSubscription?: Subscription;
  private tileCheckTimeoutId: number | null = null;
  private tilesReceived = false;
  private locationWatchId: number | null = null;

  // Same teardown signal RadarComponent uses: one Subject, fired once on
  // destroy, cancels every chain piped through takeUntil -- including a start
  // chain still in flight that never got far enough to be stored anywhere.
  private readonly destroy$ = new Subject<void>();

  private readonly handleOnline = (): void => {
    this.mode = 'GPS';
  };
  private readonly handleOffline = (): void => {
    this.mode = 'BLE';
  };

  constructor(
    private readonly router: Router,
    private readonly ngZone: NgZone,
    private readonly radarDiscoveryService: RadarDiscoveryService,
    private readonly userProfileService: UserProfileService,
    private readonly scanSessionService: ScanSessionService,
    private readonly connectionService: ConnectionService,
    private readonly toastService: ToastService,
    private readonly destinationSearchService: DestinationSearchService,
    private readonly applicationRef: ApplicationRef,
    private readonly environmentInjector: EnvironmentInjector
  ) {}

  get scanState(): ScanPresentationState {
    // Guard clause: before the first response lands this is "searching"
    // regardless of the (zero) users in the array -- "still looking" and
    // "looked, found nobody" are different messages.
    if (!this.hasReceivedFirstNearbyResponse) {
      return 'searching';
    }
    return this.discoveredUsers.length > 0 ? 'results' : 'empty';
  }

  // A destination must be PICKED from the suggestions (not just typed): only a
  // picked one carries real coordinates for route-overlap matching.
  get canStartScan(): boolean {
    return !this.isStartingScan && !this.locationDenied && this.userPosition !== null && this.selectedDestination !== null;
  }

  onDestinationInput(): void {
    // Typing after a pick means the field no longer matches what was selected.
    this.selectedDestination = null;
    this.showDestinationSuggestions = true;
    this.destinationQuery$.next(this.destinationLabel);
  }

  onDestinationFocus(): void {
    this.showDestinationSuggestions = true;
  }

  selectSuggestion(suggestion: DestinationSuggestion): void {
    this.selectedDestination = suggestion;
    this.destinationLabel = suggestion.label;
    this.showDestinationSuggestions = false;
    this.startError = null;
  }

  // A full reload (not a soft retry): the map keeps its failed tile requests
  // cached in-memory, so a fresh page is the reliable way to try the network again.
  retryTiles(): void {
    window.location.reload();
  }

  // Read-through to the single source of truth (ScanSessionService) -- the
  // rings and the card copy always show the filter the user actually set.
  get radiusKm(): number {
    return this.scanSessionService.filters.radiusKm;
  }

  ngOnInit(): void {
    this.mode = navigator.onLine ? 'GPS' : 'BLE';
    window.addEventListener('online', this.handleOnline);
    window.addEventListener('offline', this.handleOffline);

    // Redraw the rings when the radius filter changes while this screen is open.
    this.scanSessionService.filters$.pipe(takeUntil(this.destroy$)).subscribe(() => this.refreshRadar());

    // Debounce + switchMap: wait for a pause in typing, and cancel a slower,
    // now-stale search when a newer one starts (Nominatim allows ~1 req/s).
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
          return this.destinationSearchService.search(trimmed).pipe(catchError(() => of<DestinationSuggestion[]>([])));
        }),
        takeUntil(this.destroy$)
      )
      .subscribe(results => {
        this.isSearchingDestinations = false;
        this.destinationSuggestions = results;
      });

    // One-shot fix so the map can centre on the user before any scan starts.
    this.getCurrentPosition()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: position => {
          this.userPosition = { lat: position.coords.latitude, lng: position.coords.longitude };
          this.refreshRadar();
          this.recenter();
        },
        error: () => {
          this.locationDenied = true;
        }
      });
  }

  ngAfterViewInit(): void {
    // Guard clause: map tiles cannot load offline, and there is no cached
    // fallback. Go straight to the classic radar (which also owns the Bluetooth
    // offline mode) instead of showing a spinner that can never finish.
    if (!navigator.onLine) {
      this.fallBackToClassicRadar('You are offline');
      return;
    }
    void this.bootMap();
  }

  ngOnDestroy(): void {
    window.removeEventListener('online', this.handleOnline);
    window.removeEventListener('offline', this.handleOffline);
    this.destroyed = true;
    this.destroy$.next();
    this.destroy$.complete();
    this.destinationQuery$.complete();

    if (this.loadTimeoutId !== null) {
      window.clearTimeout(this.loadTimeoutId);
    }
    if (this.tileCheckTimeoutId !== null) {
      window.clearTimeout(this.tileCheckTimeoutId);
    }

    // Order matters (Teardown in reverse order of construction): the scan
    // teardown and the sweep still READ the map (clearing the sweep layer,
    // removing pins), so they must run while the map is alive. The map is
    // removed LAST. Removing it first made teardownScan() call into a dead map,
    // which throws; an exception inside ngOnDestroy aborts the router's
    // navigation and leaves a blank white screen -- exactly "I tapped Classic
    // view / accepted a request while scanning and got a white page".
    if (this.isScanning) {
      this.teardownScan();
    }
    this.stopSweep();
    Array.from(this.pinMarkers.keys()).forEach(userId => this.removePin(userId));
    this.meMarker?.remove();
    // remove() releases the WebGL context. Browsers cap live contexts at
    // roughly 8-16; leaking one per visit eventually blanks every canvas on the
    // page, including the Three.js avatars on the classic radar.
    // try/catch: a map-library hiccup while closing must never block the
    // navigation that is already in progress. Dropping the reference afterwards
    // makes every later `this.mapInstance?.` call a harmless no-op instead of a
    // call into a destroyed map.
    try {
      this.mapInstance?.remove();
    } catch (error) {
      console.warn('[radar-map] map.remove() failed during teardown:', error);
    }
    this.mapInstance = undefined;
    // Closing this screen mid-scan must flip status back to "unavailable" --
    // done above (teardownScan), BEFORE the map was removed.
  }

  // --- Map -------------------------------------------------------------------

  private async bootMap(): Promise<void> {
    this.loadTimeoutId = window.setTimeout(() => {
      if (!this.mapLoaded) {
        this.fallBackToClassicRadar('The map took too long to load');
      }
    }, MAP_LOAD_TIMEOUT_MS);

    try {
      // Code splitting: a dynamic import() makes the bundler put the map
      // library (roughly 800 KB) in its own chunk, downloaded only when this
      // route is opened. A static import at the top of the file would ship it
      // with the classic radar too, which never uses it.
      const loaded = await import('maplibre-gl');
      // The library is CommonJS: depending on the bundler's interop the real
      // exports sit on `.default` or on the namespace itself.
      this.maplibre = ((loaded as unknown as { default?: MapLibreModule }).default ?? loaded) as MapLibreModule;
    } catch {
      this.fallBackToClassicRadar('The map library could not load');
      return;
    }

    // Guard clause: the user may have left while the chunk was downloading.
    if (this.fellBack || this.destroyed) {
      return;
    }

    const center = this.userPosition ?? { lat: environment.mapDefaultLat, lng: environment.mapDefaultLng };

    // runOutsideAngular: the map fires events on every pointer move and every
    // animation frame. Inside Angular's zone each one would trigger a full
    // change-detection pass over the whole app for no visible benefit. Running
    // outside it and re-entering the zone only for real state changes (see
    // ngZone.run below) keeps the map smooth.
    this.ngZone.runOutsideAngular(() => {
      try {
        this.mapInstance = new this.maplibre!.Map({
          container: this.mapHost.nativeElement,
          style: environment.mapStyleUrl,
          center: [center.lng, center.lat],
          zoom: DEFAULT_ZOOM,
          pitch: DEFAULT_PITCH,
          bearing: DEFAULT_BEARING,
          maxPitch: 75,
          attributionControl: { compact: true }
        });
      } catch {
        // Thrown when WebGL is unavailable (old device, blocked GPU).
        this.mapInstance = undefined;
      }
    });

    const mapInstance = this.mapInstance;
    if (!mapInstance) {
      this.fallBackToClassicRadar('This device cannot draw the 3D map');
      return;
    }

    mapInstance.setPadding({ top: 0, left: 0, right: 0, bottom: SHEET_PADDING_PX });
    mapInstance.addControl(new this.maplibre.NavigationControl({ visualizePitch: true }), 'top-right');
    // The user panning or rotating means "stop dragging me back to my dot".
    mapInstance.on('dragstart', () => {
      this.followUser = false;
    });
    let loggedFirstError = false;
    mapInstance.on('error', event => {
      // Surface WHY (blocked, 403, DNS) once -- a silent failure here is what
      // makes a blocked tile host look like "the map is just pink".
      if (!loggedFirstError) {
        loggedFirstError = true;
        console.warn('[radar-map] map resource failed:', event?.error?.message ?? event);
      }
      // Individual tile errors also land here and are harmless. Only a failure
      // BEFORE first load (the style document itself) is fatal.
      if (!this.mapLoaded) {
        this.fallBackToClassicRadar('The map style could not load');
      }
    });
    // A vector tile actually arriving is the proof the tile host is reachable;
    // the style document alone loading is not (it lives on the same host but
    // tiles are separate requests that can fail independently).
    mapInstance.on('data', event => {
      if (event.dataType === 'source' && 'tile' in event) {
        this.tilesReceived = true;
      }
    });
    mapInstance.on('load', () => this.onMapLoaded());
  }

  private onMapLoaded(): void {
    const mapInstance = this.mapInstance;
    if (!mapInstance || !this.maplibre) {
      return;
    }
    this.mapLoaded = true;
    if (this.loadTimeoutId !== null) {
      window.clearTimeout(this.loadTimeoutId);
      this.loadTimeoutId = null;
    }

    // Not a fatal failure (the radar still works on a blank map), so do not
    // fall back -- but do say so, with a way out, instead of leaving a blank map.
    this.tileCheckTimeoutId = window.setTimeout(() => {
      if (this.tilesReceived || this.destroyed) {
        return;
      }
      console.warn('[radar-map] no map tile arrived; the tile host looks unreachable from this network.');
      this.ngZone.run(() => {
        this.tilesBlocked = true;
      });
    }, TILE_CHECK_DELAY_MS);

    applyVividStyle(mapInstance);
    this.addRadarLayers(mapInstance);

    const meElement = document.createElement('div');
    meElement.className = 'rm-me';
    meElement.textContent = 'Me';
    const origin = this.currentOrigin();
    this.meMarker = new this.maplibre.Marker({ element: meElement }).setLngLat([origin.lng, origin.lat]).addTo(mapInstance);

    this.refreshRadar();
    this.syncPins();
    if (this.isScanning) {
      this.startSweep();
    }

    // Back into Angular's zone: this field drives the template (hides the
    // loading overlay), and a write made outside the zone would not trigger
    // change detection.
    this.ngZone.run(() => {
      this.mapPhase = 'ready';
    });
  }

  private addRadarLayers(mapInstance: MapLibreMap): void {
    mapInstance.addSource('radar-disc', { type: 'geojson', data: EMPTY_COLLECTION });
    mapInstance.addSource('radar-rings', { type: 'geojson', data: EMPTY_COLLECTION });
    mapInstance.addSource('radar-sweep', { type: 'geojson', data: EMPTY_COLLECTION });

    mapInstance.addLayer({ id: 'radar-disc', type: 'fill', source: 'radar-disc', paint: { 'fill-color': '#7c3aed', 'fill-opacity': 0.1 } });
    mapInstance.addLayer({ id: 'radar-sweep', type: 'fill', source: 'radar-sweep', paint: { 'fill-color': '#7c3aed', 'fill-opacity': ['get', 'opacity'] } });
    // Two stacked lines per ring: a wide blurred violet one underneath is the
    // glow that keeps the ring visible on a bright map, a crisp white one on
    // top is the ring itself.
    mapInstance.addLayer({ id: 'radar-rings-glow', type: 'line', source: 'radar-rings', paint: { 'line-color': '#7c3aed', 'line-width': 10, 'line-blur': 8, 'line-opacity': 0.55 } });
    mapInstance.addLayer({ id: 'radar-rings', type: 'line', source: 'radar-rings', paint: { 'line-color': '#ffffff', 'line-width': 2.2, 'line-opacity': 0.95 } });
    mapInstance.addLayer({
      id: 'radar-ring-labels',
      type: 'symbol',
      source: 'radar-rings',
      layout: {
        'symbol-placement': 'line',
        'text-field': ['get', 'label'],
        'text-font': ['Noto Sans Bold'],
        'text-size': 11,
        'symbol-spacing': 600,
        'text-keep-upright': false
      },
      paint: { 'text-color': '#ffffff', 'text-halo-color': '#4c1d95', 'text-halo-width': 2 }
    });
  }

  // Where the radar is drawn: the user's real position once known, otherwise the
  // configured default area (so the map still shows something useful when
  // location permission is denied).
  private currentOrigin(): GeoPoint {
    return this.userPosition ?? { lat: environment.mapDefaultLat, lng: environment.mapDefaultLng };
  }

  private setSourceData(sourceId: string, data: unknown): void {
    const source = this.mapInstance?.getSource(sourceId) as GeoJSONSource | undefined;
    source?.setData(data as Parameters<GeoJSONSource['setData']>[0]);
  }

  private refreshRadar(): void {
    // Guard clause: nothing to draw on until the style and layers exist.
    if (!this.mapLoaded) {
      return;
    }
    const origin = this.currentOrigin();
    const radiusMeters = this.radiusKm * 1000;

    const discFeature: PolygonFeature = {
      type: 'Feature',
      properties: {},
      geometry: { type: 'Polygon', coordinates: [circleRing(origin, radiusMeters)] }
    };
    this.setSourceData('radar-disc', discFeature);

    const rings: FeatureCollectionOf<LineFeature> = {
      type: 'FeatureCollection',
      features: RING_FRACTIONS.map(fraction => ({
        type: 'Feature',
        properties: { label: `${Number((this.radiusKm * fraction).toFixed(2))} km` },
        geometry: { type: 'LineString', coordinates: circleRing(origin, radiusMeters * fraction) }
      }))
    };
    this.setSourceData('radar-rings', rings);

    this.meMarker?.setLngLat([origin.lng, origin.lat]);
  }

  // Sweep runs entirely outside Angular's zone -- 60 updates a second must not
  // each trigger change detection.
  private startSweep(): void {
    if (this.sweepFrameId !== null || this.prefersReducedMotion) {
      return;
    }
    const startedAt = performance.now();
    this.ngZone.runOutsideAngular(() => {
      const tick = (now: number): void => {
        this.sweepFrameId = requestAnimationFrame(tick);
        this.drawSweep(((now - startedAt) / SWEEP_PERIOD_MS) * Math.PI * 2);
      };
      this.sweepFrameId = requestAnimationFrame(tick);
    });
  }

  private stopSweep(): void {
    if (this.sweepFrameId !== null) {
      cancelAnimationFrame(this.sweepFrameId);
      this.sweepFrameId = null;
    }
    this.setSourceData('radar-sweep', EMPTY_COLLECTION);
  }

  private drawSweep(angleRad: number): void {
    const origin = this.currentOrigin();
    const radiusMeters = this.radiusKm * 1000;
    const features: PolygonFeature[] = [];
    for (let slice = 0; slice < SWEEP_SLICES; slice++) {
      const sliceEnd = angleRad - (SWEEP_SPAN_RAD * slice) / SWEEP_SLICES;
      features.push({
        type: 'Feature',
        // Opacity falls off along the trail: bright at the leading edge,
        // fading toward the tail.
        properties: { opacity: SWEEP_PEAK_OPACITY * Math.pow(1 - slice / SWEEP_SLICES, 1.6) },
        geometry: { type: 'Polygon', coordinates: [wedgeRing(origin, sliceEnd, SWEEP_SPAN_RAD / SWEEP_SLICES, radiusMeters)] }
      });
    }
    this.setSourceData('radar-sweep', { type: 'FeatureCollection', features });
  }

  recenter(): void {
    this.followUser = true;
    const mapInstance = this.mapInstance;
    if (!mapInstance || !this.userPosition) {
      return;
    }
    mapInstance.easeTo({ center: [this.userPosition.lng, this.userPosition.lat], duration: 900 });
  }

  // Reconcile markers with the latest list (add new, move existing, remove
  // gone) instead of tearing every pin down and rebuilding on each 5 s poll --
  // rebuilding would replay each pin's pop-in animation every poll.
  private syncPins(): void {
    const mapInstance = this.mapInstance;
    if (!mapInstance || !this.maplibre || !this.mapLoaded) {
      return;
    }
    const origin = this.currentOrigin();
    const seen = new Set<number>();

    for (const user of this.discoveredUsers) {
      seen.add(user.id);
      const position = this.pinPosition(origin, user);
      const existing = this.pinMarkers.get(user.id);
      if (existing) {
        existing.setLngLat(position);
        continue;
      }
      const { element, avatar } = this.createPinElement(user);
      const marker = new this.maplibre.Marker({ element, anchor: 'bottom', offset: [0, -2] }).setLngLat(position).addTo(mapInstance);
      this.pinMarkers.set(user.id, marker);
      this.pinAvatars.set(user.id, avatar);
      // First change-detection pass AFTER the pin is in the DOM: the avatar
      // sizes its WebGL canvas from the canvas element's laid-out width in
      // ngOnInit, which is 0 while the element is still detached.
      avatar.changeDetectorRef.detectChanges();
    }

    Array.from(this.pinMarkers.keys())
      .filter(userId => !seen.has(userId))
      .forEach(userId => this.removePin(userId));
  }

  private removePin(userId: number): void {
    this.pinMarkers.get(userId)?.remove();
    this.pinMarkers.delete(userId);
    const avatar = this.pinAvatars.get(userId);
    this.pinAvatars.delete(userId);
    if (!avatar) {
      return;
    }
    // destroy() runs the avatar's ngOnDestroy, which disposes its renderer and
    // WebGL context. Skipping it leaks one context per pin ever shown, and
    // browsers cap live contexts (the map itself needs one).
    this.applicationRef.detachView(avatar.hostView);
    avatar.destroy();
  }

  // Real distance, placeholder direction -- see placeholderBearingRadians.
  private pinPosition(origin: GeoPoint, user: DiscoveredUserCardDto): Position {
    const meters = Math.min(user.distance_km, this.radiusKm) * 1000;
    // Real compass bearing when the backend supplies one: 0 = north, clockwise,
    // so east = sin(b) and north = cos(b). (The placeholder below uses the
    // maths-style angle instead, hence the different axes.)
    if (typeof user.bearing_deg === 'number') {
      const compass = (user.bearing_deg * Math.PI) / 180;
      return offsetMeters(origin, Math.sin(compass) * meters, Math.cos(compass) * meters);
    }
    const bearing = placeholderBearingRadians(user.id);
    return offsetMeters(origin, Math.cos(bearing) * meters, Math.sin(bearing) * meters);
  }

  // Reuse, do not rebuild: the classic radar's standing Three.js figure
  // (UserAvatar3dComponent) is instantiated imperatively here because map
  // markers are plain DOM the map owns, not an Angular template. createComponent
  // plus attachView is the supported way to run an Angular component inside DOM
  // Angular did not render: it gets its own change detection and lifecycle, and
  // we must destroy it ourselves (see removePin).
  private createPinElement(user: DiscoveredUserCardDto): { element: HTMLElement; avatar: ComponentRef<UserAvatar3dComponent> } {
    const pin = document.createElement('button');
    pin.type = 'button';
    pin.className = 'rm-pin';
    pin.setAttribute('aria-label', `Open ${user.name}'s card`);

    const figure = document.createElement('span');
    figure.className = 'rm-pin-figure';
    const avatarHost = document.createElement('app-user-avatar-3d');
    figure.appendChild(avatarHost);
    pin.appendChild(figure);

    const avatar = createComponent(UserAvatar3dComponent, { environmentInjector: this.environmentInjector, hostElement: avatarHost });
    avatar.setInput('userId', user.id);
    this.applicationRef.attachView(avatar.hostView);

    if (user.identity_badge) {
      const tick = document.createElement('span');
      tick.className = 'rm-pin-tick';
      tick.setAttribute('aria-hidden', 'true');
      tick.innerHTML = '<svg viewBox="0 0 24 24" fill="none"><path d="M5 13l4 4L19 7" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
      figure.appendChild(tick);
    }

    const name = document.createElement('span');
    name.className = 'rm-pin-name';
    name.textContent = user.name.split(' ')[0];
    pin.appendChild(name);

    // The click comes from a DOM node the map created outside Angular's zone,
    // so re-enter the zone for the state change to render.
    pin.addEventListener('click', () => this.ngZone.run(() => this.selectUser(user)));
    return { element: pin, avatar };
  }

  // Fallback (Graceful Degradation): the classic radar needs no map tiles, so
  // it is the safe place to land. replaceUrl swaps the history entry so the
  // browser Back button does not bounce the user straight back into a broken
  // map.
  private fallBackToClassicRadar(reason: string): void {
    if (this.fellBack) {
      return;
    }
    this.fellBack = true;
    this.ngZone.run(() => {
      this.toastService.show(`${reason}. Showing the classic radar.`, 'neutral');
      void this.router.navigate(['/radar'], { replaceUrl: true });
    });
  }

  // --- Scan lifecycle ----------------------------------------------------------

  startScan(): void {
    // Guard clause: mirrors the button's disabled state so a stale click queued
    // before re-render cannot bypass it.
    const destination = this.selectedDestination;
    if (!this.canStartScan || !destination) {
      return;
    }
    this.isStartingScan = true;
    this.startError = null;

    this.getCurrentPosition()
      .pipe(
        switchMap(position =>
          this.scanSessionService
            .startScan({
              // The PICKED place's real coordinates -- discovery compares the
              // direction to this against everyone else's destination.
              destination_lat: destination.lat,
              destination_lng: destination.lng,
              destination_label: destination.label,
              departure_time: new Date().toISOString()
            })
            .pipe(map((scanResponse: ScanStartResponse) => ({ position, scanResponse })))
        ),
        switchMap(({ position, scanResponse }) => {
          this.sessionId = scanResponse.session_id;
          // Seeds scan:{id}:location before the first poll -- without it the
          // server answers "nobody nearby" forever (see RadarComponent.startScan).
          return this.radarDiscoveryService.updateLocation({ lat: position.coords.latitude, lng: position.coords.longitude });
        }),
        // Status flips to "looking" only AFTER a real session exists, so a
        // failed start never leaves the user marked looking with no scan.
        switchMap(() => this.userProfileService.updateProfile({ status: 'looking' })),
        takeUntil(this.destroy$)
      )
      .subscribe({
        next: () => {
          this.isStartingScan = false;
          this.isScanning = true;
          this.hasReceivedFirstNearbyResponse = false;
          this.startSweep();
          this.beginWatchingLocation();
          this.beginLocationHeartbeat();
          this.beginPolling();
        },
        error: (error: ErrorEnvelope | LocationError) => {
          this.isStartingScan = false;
          this.startError = this.isGeolocationError(error) ? 'Location permission is required to start a scan.' : error.message;
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

  connectionStateFor(userId: number): ConnectionState {
    return this.connectionStates.get(userId) ?? 'default';
  }

  sendRequestTo(userId: number): void {
    // Guard clause: a second tap while the first POST is in flight, or on an
    // already-sent card, must not create a second request.
    if (this.connectionStateFor(userId) !== 'default') {
      return;
    }
    this.connectionStates.set(userId, 'sending');
    this.connectionService
      .sendRequest(userId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.connectionStates.set(userId, 'sent');
        },
        error: (error: ErrorEnvelope) => {
          this.connectionStates.set(userId, 'default');
          this.toastService.show(error.message, 'error');
        }
      });
  }

  trackByUserId(_index: number, user: DiscoveredUserCardDto): number {
    return user.id;
  }

  initials(name: string): string {
    return name.charAt(0).toUpperCase();
  }

  // watchPosition (not a timer re-calling getCurrentPosition) fires only when
  // the device actually has a new fix, instead of polling GPS hardware on a
  // fixed clock whether or not the user moved.
  private beginWatchingLocation(): void {
    if (!navigator.geolocation) {
      return;
    }
    this.locationWatchId = navigator.geolocation.watchPosition(
      position => {
        this.userPosition = { lat: position.coords.latitude, lng: position.coords.longitude };
        this.refreshRadar();
        this.syncPins();
        if (this.followUser) {
          this.recenter();
        }
        // Best-effort: one missed update is not worth an error; the next fix retries.
        this.radarDiscoveryService.updateLocation({ lat: position.coords.latitude, lng: position.coords.longitude }).subscribe({ error: () => undefined });
      },
      () => undefined,
      { enableHighAccuracy: false, maximumAge: 10_000, timeout: 15_000 }
    );
  }

  // See LOCATION_HEARTBEAT_MS. Re-sends the newest known position on a fixed
  // clock so the server-side 30 s TTL never expires on a stationary user.
  private beginLocationHeartbeat(): void {
    this.heartbeatSubscription?.unsubscribe();
    this.heartbeatSubscription = interval(LOCATION_HEARTBEAT_MS)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        // Guard clause: no fix yet, nothing meaningful to send.
        if (!this.userPosition) {
          return;
        }
        // Best-effort: a missed beat is covered by the next one.
        this.radarDiscoveryService.updateLocation({ lat: this.userPosition.lat, lng: this.userPosition.lng }).subscribe({ error: () => undefined });
      });
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
          // Catch INSIDE the projection: an error escaping switchMap would end
          // the outer interval for good, silently freezing the radar on the
          // first failed poll. Caught here, only this tick is skipped.
          this.radarDiscoveryService.getNearby().pipe(catchError(() => of(this.discoveredUsers)))
        )
      )
      .subscribe({
        next: users => {
          this.hasReceivedFirstNearbyResponse = true;
          this.discoveredUsers = users;
          this.syncPins();
        }
      });
  }

  private teardownScan(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = undefined;
    this.heartbeatSubscription?.unsubscribe();
    this.heartbeatSubscription = undefined;
    this.stopWatchingLocation();
    this.stopSweep();

    const hadActiveSession = this.sessionId !== null;
    this.sessionId = null;
    this.isScanning = false;
    this.discoveredUsers = [];
    this.selectedUser = null;
    this.hasReceivedFirstNearbyResponse = false;
    this.syncPins();

    // Guard clause: nothing server-side to clean up if a session never existed.
    if (!hadActiveSession) {
      return;
    }
    // Best-effort cleanup: the UI is already back to idle; a failed cleanup call
    // is not something the user asked to see.
    this.radarDiscoveryService.stopScan().subscribe({ error: () => undefined });
    this.userProfileService.updateProfile({ status: 'unavailable' }).subscribe({ error: () => undefined });
  }

  private getCurrentPosition(): Observable<GeolocationPosition> {
    return new Observable<GeolocationPosition>(subscriber => {
      if (!navigator.geolocation) {
        const unsupported: LocationError = { code: 0, message: 'Geolocation is not supported on this device.' };
        subscriber.error(unsupported);
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
