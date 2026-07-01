Show the current implementation status of all 96 tasks.

## Step 1 — Read PROGRESS.md

Open PROGRESS.md. This is the source of truth for task completion. Count:
- `[x]` = DONE
- `[ ]` = PENDING (or PARTIAL if code exists but criterion is known to be unmet)

## Step 2 — Output by phase

For each phase, list tasks:
```
Phase 0 — Foundation & Contracts
  [x] T001 — Monorepo Scaffold
  [ ] T002 — Kafka Contracts
  ...
```

Use `[x]` exactly as in PROGRESS.md — do not infer status from file existence.

## Step 3 — Summary

```
Done:     X / 96
Pending:  Y / 96
```

## Step 4 — Ready to implement

List tasks where:
- Status is `[ ]` in PROGRESS.md
- All "Depends On" tasks (from SPECS.md) are `[x]` in PROGRESS.md

Format:
```
Ready to implement:
  T002 — Kafka Contracts (depends on T001 ✓)
  T003 — Shared Error Envelope (depends on T001 ✓)
  T004 — Docker Compose (depends on T001 ✓)
```

## Step 5 — Blocked

List tasks that are `[ ]` and have at least one `[ ]` dependency:
```
Blocked:
  T005 — OpenAPI Specs → waiting on T002, T003
  T006 — SQL Schemas → waiting on T004
```

Keep the output tight — this is a status check, not a report.
