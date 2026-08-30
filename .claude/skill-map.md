# Skill Map — Task to Required Skills

Each task lists the skill files that must be injected into the agent's context before implementation.
Skill files live in `.claude/skills/`. Empty = no special skill needed (use CLAUDE.md + SPECS.md only).

## Phase 0 — Foundation & Contracts
```
T001:
T002: kafka-outbox
T003: java-spring-boot dotnet-mvc-controllers
T004:
T005:
```

## Phase 1 — Infrastructure
```
T006: java-spring-boot dotnet-mvc-controllers
T007:
T008:
T009: kafka-outbox
T010:
```

## Phase 2 — API Gateway (.NET)
```
T011: dotnet-mvc-controllers
T012: dotnet-mvc-controllers
T013: dotnet-mvc-controllers
T014:
```

## Phase 3 — User Service (.NET)
```
T015: dotnet-mvc-controllers
T016: dotnet-mvc-controllers
T017: dotnet-mvc-controllers
T018: dotnet-mvc-controllers
T019: dotnet-mvc-controllers
T020: dotnet-mvc-controllers kafka-outbox
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
T035: dotnet-mvc-controllers
T036: dotnet-mvc-controllers
T037: dotnet-mvc-controllers
T038: dotnet-mvc-controllers kafka-outbox
T039: dotnet-mvc-controllers kafka-outbox
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
T047: dotnet-mvc-controllers
T048: dotnet-mvc-controllers
T049: dotnet-mvc-controllers kafka-outbox
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
T082: dotnet-mvc-controllers
T083: java-spring-boot
T084: dotnet-mvc-controllers
T085: angular-pwa
T086:
```

## Phase 13 — Observability
```
T087: java-spring-boot
T088: dotnet-mvc-controllers
T089:
T090: java-spring-boot dotnet-mvc-controllers
```

## Phase 14 — Saga + Outbox
```
T091: java-spring-boot kafka-outbox saga
T092: dotnet-mvc-controllers kafka-outbox
T093: java-spring-boot kafka-outbox saga
T094: dotnet-mvc-controllers kafka-outbox
T095: java-spring-boot kafka-outbox
T096: java-spring-boot dotnet-mvc-controllers kafka-outbox saga
```
