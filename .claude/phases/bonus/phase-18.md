# Phase 18 — Mandatory Profile Setup (Bonus, beyond the original 96 tasks)

**Goal:** REQUIREMENTS.md §1 defines onboarding as four stages — Google sign-in, phone OTP,
**mandatory** profile setup (name, photo, preferred language, gender), then an *optional*
identity-verification badge. T063 (Auth Flow) built the first two and explicitly stopped at
"JWT stored, user authenticated," expecting a later Phase 11 ticket to build the third — but no
ticket in the original T062–T072 list does. T070 ("Profile + Settings") only covers editing an
already-complete profile. Confirmed against the backend's own gate, not just the product
requirement: `User.IsProfileComplete()`/`TryCompleteOnboarding()` (user-service) require name,
photo, preferred language, and a real gender before `IsOnboardingComplete` ever flips true — until
then, the `user.verified` Kafka event never fires and the user never appears in Discovery. This
phase closes that gap so a newly-signed-up traveler has somewhere to go after OTP, and so Radar
(T064) ships with a real, reachable precondition instead of landing new users on a dead end.
Identity Verification (Figma Screen 05) is correctly optional by design and is deliberately NOT
part of this phase.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T109 | Profile Setup (Onboarding) | angular-pwa figma-compliance |

**Phase complete when:** a user who finishes Google sign-in + OTP lands on a real screen that
collects whatever Google didn't already supply (photo if missing, preferred language, gender),
persists it via the existing T018 endpoints, reissues the access token when onboarding completes,
and is gated by `AuthGuard` — establishing the pattern T064 and every later protected route must
follow.
