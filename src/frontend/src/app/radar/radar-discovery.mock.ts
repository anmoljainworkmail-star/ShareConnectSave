import { DiscoveredUserCardDto } from './models/radar.models';

// Dev-only testing aid (explicitly requested: "add some dummy nearby user to
// test our scan") -- kept in its own file (Single Responsibility) so it is
// obviously NOT part of the real wire contract RadarDiscoveryService talks to
// discovery-service with. This is a different concern from the "no fabricated
// data for a feature that doesn't exist server-side" convention in
// radar.models.ts: discovery-service's /scan/nearby DOES return exactly this
// shape today -- there just isn't a second real human test account seeded in
// a solo-developer's local environment to be discovered by it. RadarDiscoveryService
// only reaches for this list when a real scan legitimately comes back empty,
// and never in a production build (see its environment.production guard).
export const MOCK_NEARBY_USERS: ReadonlyArray<DiscoveredUserCardDto> = [
  {
    id: -1001,
    name: 'Ananya Rao',
    photo_url: null,
    identity_badge: true,
    distance_km: 0.3,
    unavailable: false,
    rating_average: 4.8,
    rating_count: 23,
    trusted: true,
    destination_label: 'Connaught Place',
    leaving_in_minutes: 5,
    route_match_percent: 92,
    preferred_language: 'Hindi'
  },
  {
    id: -1002,
    name: 'Karan Mehta',
    photo_url: null,
    identity_badge: true,
    distance_km: 0.6,
    unavailable: false,
    rating_average: 4.2,
    rating_count: 11,
    trusted: false,
    destination_label: 'Cyber Hub',
    leaving_in_minutes: 12,
    route_match_percent: 78,
    preferred_language: 'English'
  },
  {
    id: -1003,
    name: 'Priya Singh',
    photo_url: null,
    identity_badge: false,
    distance_km: 0.9,
    unavailable: false
    // Deliberately no rating/destination/etc. -- exercises the *ngIf-away
    // path in user-card.component.html for a card the backend contract
    // genuinely can't fill in yet.
  },
  {
    id: -1004,
    name: 'Rohan Gupta',
    photo_url: null,
    identity_badge: true,
    distance_km: 1.2,
    unavailable: true,
    rating_average: 3.9,
    rating_count: 6,
    destination_label: 'Rajiv Chowk',
    leaving_in_minutes: 20
  }
];
