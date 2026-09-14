package com.shareconnectsave.discovery.scan;

import com.shareconnectsave.discovery.scan.domain.ScanLocation;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

// Stateless helper, deliberately NOT behind an interface: this project's own
// CLAUDE.md warns against forcing a Strategy/interface shape where a plain
// @Component is the honest one — nothing here is ever swapped at runtime,
// there is exactly one way to compute a haversine distance or a bearing, so
// an interface would only add ceremony with no substitutable behaviour
// behind it. Compare with ScanSessionService, which genuinely IS behind an
// interface because ScanController depends on it via Dependency Inversion.
@Component
public class ScanFilterService {

    // Mean Earth radius in kilometres — the constant the haversine formula
    // scales its unit-sphere angular distance by to get a real-world km value.
    private static final double EARTH_RADIUS_KM = 6371.0;

    // Geospatial Query Pattern — Haversine formula: great-circle distance
    // between two lat/lng points on a sphere. Plain English: a flat ruler
    // measurement on lat/lng values overstates or understates real distance
    // depending on latitude, because the Earth is curved, not flat — the
    // same reason a straight line on a paper map isn't the true shortest
    // path between two cities on a globe. Haversine corrects for that
    // curvature; this is why "how far apart are these two GPS fixes" is
    // never plain Euclidean distance on the raw numbers.
    public double distanceKm(ScanLocation from, ScanLocation to) {
        double lat1 = Math.toRadians(from.lat());
        double lat2 = Math.toRadians(to.lat());
        double dLat = Math.toRadians(to.lat() - from.lat());
        double dLng = Math.toRadians(to.lng() - from.lng());

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    // Bearing: compass direction (0-360 degrees, 0 = due north, measured
    // clockwise) from one point to another — the same number a ship's
    // compass would read if pointed from the first point toward the second.
    private double bearingDegrees(double fromLat, double fromLng, double toLat, double toLng) {
        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double dLng = Math.toRadians(toLng - fromLng);

        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    // Route Overlap: "is the candidate's destination roughly in the same
    // direction I'm already heading" — this project's actual "same
    // direction" framing (CLAUDE.md's product description), not a generic
    // route-matching algorithm. Measured as the similarity between two
    // bearings taken from the SAME origin — the caller's current position:
    // (a) caller's current position -> the caller's own destination, and
    // (b) caller's current position -> the candidate's destination. An
    // angular difference of 0 degrees means "identical direction"; 180
    // degrees means "exact opposite direction" — converted to a 0..1
    // similarity score so it can be compared against one configurable
    // threshold the same way the distance and departure-time filters are.
    public double routeOverlap(
            ScanLocation callerLocation,
            double callerDestLat, double callerDestLng,
            double candidateDestLat, double candidateDestLng) {
        double bearingToOwnDestination =
                bearingDegrees(callerLocation.lat(), callerLocation.lng(), callerDestLat, callerDestLng);
        double bearingToCandidateDestination =
                bearingDegrees(callerLocation.lat(), callerLocation.lng(), candidateDestLat, candidateDestLng);

        double angularDifference = Math.abs(bearingToOwnDestination - bearingToCandidateDestination);
        if (angularDifference > 180) {
            angularDifference = 360 - angularDifference;
        }

        // 1.0 = heading in the exact same direction; 0.0 = perpendicular or
        // opposite (>=180 degrees apart) — see this method's own comment.
        return 1.0 - (angularDifference / 180.0);
    }

    // Departure Time Window: +/- N minutes, both directions. Null-safe by
    // EXCLUSION (fail closed), not by skipping the check: if either session
    // never set a departure_time, there is nothing meaningful to compare, so
    // this predicate treats that candidate as a mismatch rather than letting
    // an absent value silently pass every window check — the simpler of the
    // two options the ticket allows, and the one that never crashes on a
    // null Instant.
    public boolean withinDepartureWindow(Instant callerDepartureTime, Instant candidateDepartureTime, long windowMinutes) {
        if (callerDepartureTime == null || candidateDepartureTime == null) {
            return false;
        }

        long diffMinutes = Math.abs(Duration.between(callerDepartureTime, candidateDepartureTime).toMinutes());
        return diffMinutes <= windowMinutes;
    }
}
