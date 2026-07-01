# Travel Companion Discovery Platform (MVP)

## Vision

A platform that helps travelers discover other verified people nearby
who are heading in the same direction. The app only facilitates
discovery, matching, and temporary communication. It does **not**
provide transportation, payments, or ride tracking.

## Core Principle

Our responsibility ends once both users have met. After the meeting: -
Temporary chat expires. - Users rate each other. - No further
involvement.

## MVP Workflow

1.  Google Sign-In + phone verification.
2.  User grants location.
3.  User enters destination.
4.  User taps **Start Scan**.
5.  Radar animation searches for compatible nearby users.
6.  Nearby compatible users appear on the radar.
7.  User taps a profile.
8.  View summary (rating, destination, ETA, route match).
9.  Send request.
10. Recipient accepts or declines.
11. Temporary chat opens.
12. Users decide a meeting point.
13. User marks **Met Successfully**.
14. Chat auto-closes after a short period.
15. Both users rate each other.

## Discovery UI

-   Animated radar similar to Bluetooth/AirDrop discovery.
-   Current user at center.
-   Compatible users appear as animated dots/cards.
-   Discovery filters:
    -   Nearby radius (default 1 km)
    -   Similar route
    -   Departure time window
    -   Not blocked
    -   Looking for companion

## User Card

-   Name
-   Profile photo
-   Rating
-   Destination
-   Leaving in X minutes
-   Route match %
-   Preferred language
-   Send Request button

## Temporary Chat

-   Opens only after mutual acceptance.
-   No permanent message history.
-   Auto-expires after meeting or timeout.

## Rating System

Positive tags: - Friendly - Respectful - On time - Good communication -
Would travel again

Negative tags: - Didn't show up - Fake destination - Abusive - Spam -
Asked for money

Restrictions: - High-rated users receive a trusted badge. - Repeated
poor ratings/reports reduce request limits. - Very low-rated accounts go
to manual review.

## Out of Scope (MVP)

-   Ride booking
-   Cab integration
-   Payments
-   Fare splitting
-   Live ride tracking
-   Driver onboarding

## Suggested Tech Stack

Frontend: - Angular

Backend: - .NET 9 Minimal APIs

Realtime: - SignalR

Authentication: - Google Sign-In + JWT

Databases: - SQL Server (users, ratings, requests) - MongoDB (temporary
chat)

Messaging: - Kafka

Maps: - Google Maps Platform or Mapbox

Notifications: - Firebase Cloud Messaging

## Future Ideas

-   Women-only mode
-   Airport, railway station, bus stand support
-   Office commute
-   Event travel
-   AI route recommendations
-   Enterprise commuting
