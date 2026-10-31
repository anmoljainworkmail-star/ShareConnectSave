# Phase 19 — Optional Identity Verification (Bonus, beyond the original 96 tasks)

**Goal:** Unlike Phase 18's mandatory Profile Setup, Identity Verification (Figma Screen 05) is
explicitly optional per REQUIREMENTS.md §1 and does not gate Discovery visibility —
`User.IdentityBadge` is tracked entirely separately from `IsOnboardingComplete`, by design, so a
fully discoverable user may never attempt it. Confirmed non-blocking against T025's Kafka
consumer, which gates Discovery on `user.verified` (phone + profile complete) and never reads
`identity_badge`. Because nothing downstream depends on this ticket, it is sequenced last — after
every other Phase 11/18 Angular ticket — instead of inserted early the way Phase 18 was. The
backend has existed since T019 (Phase 3) with nothing in the frontend ever calling it.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T110 | Identity Verification (Selfie/Liveness Badge) | angular-pwa figma-compliance |

**Phase complete when:** a user can reach a "Verify your identity" entry point from
Profile/Settings (only when not already verified), submit a selfie, and see their `identity_badge`
reflected immediately on success — with `NO_BASE_PHOTO` and `IDENTITY_MISMATCH` handled per the
real shipped controller (no polling loop against the openapi spec's undocumented, unimplemented
`GET /identity/status`).
