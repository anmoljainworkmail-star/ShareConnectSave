# Phase 3 — User Service (.NET)

**Goal:** Identity, auth, profile management, and the first Kafka event. After T020, the Discovery Service can start tracking verified users.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T015 | User Service Project + EF Core Setup | dotnet-minimal-api |
| T016 | Google OAuth + JWT Issuance | dotnet-minimal-api |
| T017 | Phone OTP Verification | dotnet-minimal-api |
| T018 | Profile CRUD | dotnet-minimal-api |
| T019 | Identity Verification Badge | dotnet-minimal-api |
| T020 | Kafka Producer: user.verified | dotnet-minimal-api kafka-outbox |

**Phase complete when:** Full auth flow works end-to-end (Google sign-in → OTP → profile → identity verify → user.verified event in Kafka).
