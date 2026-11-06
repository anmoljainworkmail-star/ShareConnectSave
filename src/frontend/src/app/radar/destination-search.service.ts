import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

// Real, free-text place lookup for the destination field's Google Maps/
// Rapido-style autocomplete. Single Responsibility (S): this service only
// knows how to turn a query string into ranked real places, and a
// coordinate pair back into a place label -- RadarComponent owns deciding
// WHEN to call it (debounce, minimum query length, canceling a stale
// in-flight call) and what to do with the result.
//
// OpenStreetMap Nominatim (nominatim.org), not Google Places: this
// project's tech stack names Google Maps Platform, but that requires a
// billing-enabled Google Cloud API key this environment doesn't have.
// Nominatim needs no key at all, so "type hyd, get Hyderabad" works today
// against a real place database instead of a hand-picked dummy list.
// Swapping this service's internals for a real Google Places call later is
// a drop-in replacement -- RadarComponent only depends on this class's two
// method signatures, not on which provider answers them. Tracked in
// .claude/notes/follow-ups.md "From T064" alongside the related geocoding
// gap.
//
// Usage-policy note (nominatim.org/release-docs/latest/api/Usage-Policy):
// the public endpoint is rate-limited to roughly one request/second per IP
// and is meant for light, non-commercial use -- acceptable for this
// project's local-dev/learning scope, NOT how a shipped product should call
// it (a real release would proxy this through its own backend or move to a
// licensed provider). RadarComponent's debounceTime + distinctUntilChanged
// keeps keystroke-driven request volume well under that limit.
export interface DestinationSuggestion {
  readonly label: string;
  readonly subLabel: string;
  readonly lat: number;
  readonly lng: number;
}

interface NominatimPlace {
  display_name: string;
  lat: string;
  lon: string;
}

@Injectable({ providedIn: 'root' })
export class DestinationSearchService {
  private static readonly BASE_URL = 'https://nominatim.openstreetmap.org';

  constructor(private readonly http: HttpClient) {}

  search(query: string): Observable<DestinationSuggestion[]> {
    return this.http
      .get<NominatimPlace[]>(`${DestinationSearchService.BASE_URL}/search`, {
        params: { format: 'jsonv2', q: query, addressdetails: '0', limit: '6' }
      })
      .pipe(map(places => places.map(place => toSuggestion(place))));
  }

  // Reverse geocode (lat/lng -> human-readable label) for "Use current
  // location" -- the GPS coordinates passed in are the ones actually sent
  // onward as the destination, not whatever centroid Nominatim's matched
  // address returns, so the label is cosmetic only and never drifts from
  // the real fix.
  reverseGeocode(lat: number, lng: number): Observable<DestinationSuggestion> {
    return this.http
      .get<NominatimPlace>(`${DestinationSearchService.BASE_URL}/reverse`, {
        params: { format: 'jsonv2', lat: String(lat), lon: String(lng) }
      })
      .pipe(map(place => toSuggestion(place, lat, lng)));
  }
}

function toSuggestion(place: NominatimPlace, exactLat?: number, exactLng?: number): DestinationSuggestion {
  const [primary, ...rest] = place.display_name.split(',').map(part => part.trim());
  return {
    label: primary,
    subLabel: rest.join(', ') || place.display_name,
    lat: exactLat ?? Number(place.lat),
    lng: exactLng ?? Number(place.lon)
  };
}
