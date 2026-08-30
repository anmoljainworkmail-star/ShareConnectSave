# Phase 3 — User Service (.NET)

**Goal:** Identity, auth, profile management, and the first Kafka event. After T020, the Discovery Service can start tracking verified users.

**Tasks in order:**
| ID | Title | Skills |
|----|-------|--------|
| T015 | User Service Project + EF Core Setup | dotnet-mvc-controllers |
| T016 | Google OAuth + JWT Issuance | dotnet-mvc-controllers |
| T017 | Phone OTP Verification | dotnet-mvc-controllers |
| T018 | Profile CRUD | dotnet-mvc-controllers |
| T019 | Identity Verification Badge | dotnet-mvc-controllers |
| T020 | Kafka Producer: user.verified | dotnet-mvc-controllers kafka-outbox |

**Phase complete when:** Full auth flow works end-to-end (Google sign-in → OTP → profile → identity verify → user.verified event in Kafka).
