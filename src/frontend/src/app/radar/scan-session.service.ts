import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

import {
  DEFAULT_DISCOVERY_FILTERS,
  DiscoveryFilters,
  ScanStartRequest,
  ScanStartResponse
} from './models/radar.models';
import { RadarDiscoveryService } from './radar-discovery.service';

// Single source of truth. Analogy: a restaurant's one order ticket -- the
// waiter (filters panel) writes on it, the kitchen (POST /scan/start) reads
// from it, and nobody keeps a private second copy that could say something
// different. Without this, the panel would hold "1 km" in its own fields
// while the scan-start code read a different, stale value, and the user
// would see one radius and get another.
//
// Single Responsibility (S): this class owns the CURRENT FILTER STATE and
// the rule "scan-start always carries it". It does not draw controls (the
// panel's job) and does not know how HTTP works (RadarDiscoveryService's
// job) -- it is the seam between the two.
//
// State via BehaviorSubject (angular-pwa skill, no NgRx): it always holds a
// current value (so a reader never sees "nothing yet"), and filters$ lets
// any future subscriber react to changes. providedIn: 'root' makes it a
// DI-container singleton, so the radar screen and anything else that
// injects it share the exact same instance for the life of the app.
@Injectable({ providedIn: 'root' })
export class ScanSessionService {
  private readonly filtersSubject = new BehaviorSubject<DiscoveryFilters>({ ...DEFAULT_DISCOVERY_FILTERS });

  readonly filters$: Observable<DiscoveryFilters> = this.filtersSubject.asObservable();

  constructor(private readonly radarDiscoveryService: RadarDiscoveryService) {}

  get filters(): DiscoveryFilters {
    return this.filtersSubject.value;
  }

  // Copies on the way in: a caller keeping a reference to the object it
  // passed (the panel's draft) must not be able to mutate the stored state
  // behind this service's back.
  updateFilters(filters: DiscoveryFilters): void {
    this.filtersSubject.next({ ...filters });
  }

  // "Filter changes apply on next scan start": filters are read HERE, at the
  // moment a scan actually starts -- not when the user taps Apply -- so an
  // edit never retroactively alters an already-running session. The caller
  // supplies only the per-scan facts (destination, departure time); it
  // cannot forget or override the stored filters, which is what keeps this
  // the single integration point for POST /scan/start.
  startScan(
    request: Omit<ScanStartRequest, 'women_only' | 'radius_km' | 'departure_window_minutes'>
  ): Observable<ScanStartResponse> {
    const filters = this.filters;
    return this.radarDiscoveryService.startScan({
      ...request,
      women_only: filters.womenOnly,
      radius_km: filters.radiusKm,
      departure_window_minutes: filters.departureWindowMinutes
    });
  }
}
