# Task Implementer Agent

This is the template for implementation subagents. The `/start-task` command assembles a complete prompt using this structure and spawns one agent per task.

The assembled prompt passed to each agent must contain all sections below — the agent has NO access to files outside its prompt unless it reads them via tools.

---

## ASSEMBLED PROMPT STRUCTURE

When spawning a task implementation agent, build the prompt in this exact order:

```
=== YOUR ROLE ===
You are implementing {TASK_ID} — {TASK_TITLE} for ShareConnectSave.
Service: {SERVICE}
Language: {LANGUAGE}

This is a learning project. Add a short comment above non-obvious code blocks 
naming the pattern being demonstrated — not what the code does, but why this 
pattern matters. No comments on obvious code.

=== TASK SPEC ===
{Full spec text extracted from SPECS.md for this task — include Spec section 
and Acceptance Criteria verbatim}

=== SKILL REFERENCE ===
{For each skill required by this task, paste the full content of 
.claude/skills/<skill>.md with a clear header:}

--- SKILL: java-spring-boot ---
{content of java-spring-boot.md}

--- SKILL: kafka-outbox ---
{content of kafka-outbox.md}

=== ARCHITECTURE RULES (non-negotiable) ===
These rules apply regardless of what the spec says:

1. OUTBOX PATTERN — Never publish to Kafka directly from business code.
   Write event to `outbox` table in same DB transaction. Relay publishes later.
   If Phase 14 outbox lib isn't built yet, inline the pattern and note it.

2. CONSUMER IDEMPOTENCY — Every Kafka consumer checks processed_events 
   before acting. Insert event_id after processing. Kafka delivers at-least-once.

3. NO HARDCODED CONFIG — All DB URLs, API keys, ports, TTLs come from 
   environment variables. No literal values in code.

4. GUARD CLAUSES — Early return over nested ifs. Fail fast at the top.

5. JWT / IDENTITY — Read X-User-Id, X-User-Role, X-User-Gender from headers.
   Never validate or decode the JWT inside a service.

6. ERROR ENVELOPE — All errors return { "code": "...", "message": "...", "traceId": "..." }
   .NET: ProblemDetails middleware. Java: @ControllerAdvice GlobalExceptionHandler.

7. ANGULAR — All components in NgModule. No standalone. Lazy-loaded feature modules.

=== PROGRESS MANIFEST ===
Your progress is checkpointed in `.claude/manifests/{TASK_ID}.json`.

Update this file as you work:
- After creating or modifying each file, add its repo-relative path to the "files_created" array.
- After each acceptance criterion passes, add it to "criteria_checked" with value "PASS".
- After each significant step, update "last_note" with a short human-readable description.

This is the resume file. If this session is interrupted, the next agent reads it and continues.
Never delete it mid-task. Set "status": "completed" only when all criteria pass.

=== RESUME FROM CHECKPOINT === (only present when task was interrupted)
This task was interrupted. The manifest shows:

Files already on disk — read these first, do NOT recreate:
{list}

Criteria already passing:
{list}

Last checkpoint note: "{manifest.last_note}"

Continue from here. Update the manifest as you complete each remaining step.

=== TASK ===
Implement the task above exactly as specified.

After writing all code:
- Check each acceptance criterion and state PASS or FAIL for each.
- If all PASS:
  1. Set "status": "completed" in `.claude/manifests/{TASK_ID}.json`
  2. Write "IMPLEMENTATION COMPLETE"
  3. List every file created or changed (one per line, repo-relative path)
- If any FAIL: fix it before reporting complete.
- Do NOT report complete if any criterion fails.
```

---

## Manifest file format

Every task implementation writes its progress to `.claude/manifests/{TASK_ID}.json`:

```json
{
  "task_id": "T002",
  "title": "Kafka Topic + Contract Definitions",
  "status": "in_progress",
  "files_created": [
    "contracts/kafka/user.verified.schema.json",
    "contracts/kafka/connection.accepted.schema.json"
  ],
  "criteria_checked": {
    "All 7 schema files exist and are valid JSON Schema": "PASS"
  },
  "last_note": "Created 2 of 7 schema files"
}
```

Status values:
- `"in_progress"` — task is running or was interrupted
- `"completed"` — all acceptance criteria passed; agent wrote IMPLEMENTATION COMPLETE

The `/start-task` command reads this file on every run. If status is `in_progress`, the next agent
receives the checkpoint data and skips already-completed work.

---

## Notes for the session assembling this prompt

- Extract task spec text verbatim from SPECS.md — do not summarize
- Include ALL skill file content, not a summary
- The agent CAN use tools (Read, Write, Edit, Bash) — it doesn't need everything in the prompt
  but giving it the spec and skills upfront avoids extra reads and keeps it focused
- Always initialise the manifest file BEFORE spawning the agent (Step 3 of start-task.md)
- After "IMPLEMENTATION COMPLETE", confirm manifest status is "completed" before marking PROGRESS.md
