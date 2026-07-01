# Skill Map — Task to Required Skills

Each task lists the skill files that must be injected into the agent's context before implementation.
Skill files live in `.claude/skills/`. Empty = no special skill needed (use CLAUDE.md + SPECS.md only).

## Phase 0 — Foundation & Contracts
```
T001:
T002: kafka-outbox
T003: java-spring-boot dotnet-minimal-api
T004:
T005:
```

## Phase 1 — Infrastructure
```
T006: java-spring-boot dotnet-minimal-api
T007:
T008:
T009: kafka-outbox
T010:
```

## Phase 2 — API Gateway (.NET)
```
T011: dotnet-minimal-api
T012: dotnet-minimal-api
T013: dotnet-minimal-api
T014:
```

## Phase 3 — User Service (.NET)
```
T015: dotnet-minimal-api
T016: dotnet-minimal-api
T017: dotnet-minimal-api
T018: dotnet-minimal-api
T019: dotnet-minimal-api
T020: dotnet-minimal-api kafka-outbox
```

## Phase 4 — Discovery Service (Java)
```
T021: java-spring-boot
T022: java-spring-boot
T023: java-spring-boot
T024: java-spring-boot kafka-outbox
T025: java-spring-boot kafka-outbox
T026: java-spring-boot
T027: java-spring-boot
T028:
```

## Phase 5 — Connection Service (Java)
```
T029: java-spring-boot
T030: java-spring-boot
T031: java-spring-boot
T032: java-spring-boot kafka-outbox
T033: java-spring-boot kafka-outbox
T034:
```

## Phase 6 — Chat Service (.NET)
```
T035: dotnet-minimal-api
T036: dotnet-minimal-api
T037: dotnet-minimal-api
T038: dotnet-minimal-api kafka-outbox
T039: dotnet-minimal-api kafka-outbox
T040:
```

## Phase 7 — Rating Service (Java)
```
T041: java-spring-boot
T042: java-spring-boot
T043: java-spring-boot
T044: java-spring-boot kafka-outbox
T045: java-spring-boot
T046:
```

## Phase 8 — Notification Service (.NET)
```
T047: dotnet-minimal-api
T048: dotnet-minimal-api
T049: dotnet-minimal-api kafka-outbox
T050:
```

## Phase 9 — Report Service (Java)
```
T051: java-spring-boot
T052: java-spring-boot
T053: java-spring-boot
T054: java-spring-boot kafka-outbox
T055:
```

## Phase 10 — Admin Service (Java)
```
T056: java-spring-boot
T057: java-spring-boot
T058: java-spring-boot
T059: java-spring-boot
T060: java-spring-boot
T061:
```

## Phase 11 — Angular Frontend
```
T062: angular-pwa
T063: angular-pwa
T064: angular-pwa
T065: angular-pwa
T066: angular-pwa
T067: angular-pwa
T068: angular-pwa
T069: angular-pwa
T070: angular-pwa
T071: angular-pwa
T072: angular-pwa
```

## Phase 12 — Testing
```
T081: java-spring-boot
T082: dotnet-minimal-api
T083: java-spring-boot
T084: dotnet-minimal-api
T085: angular-pwa
T086:
```

## Phase 13 — Observability
```
T087: java-spring-boot
T088: dotnet-minimal-api
T089:
T090: java-spring-boot dotnet-minimal-api
```

## Phase 14 — Saga + Outbox
```
T091: java-spring-boot kafka-outbox saga
T092: dotnet-minimal-api kafka-outbox
T093: java-spring-boot kafka-outbox saga
T094: dotnet-minimal-api kafka-outbox
T095: java-spring-boot kafka-outbox
T096: java-spring-boot dotnet-minimal-api kafka-outbox saga
```
