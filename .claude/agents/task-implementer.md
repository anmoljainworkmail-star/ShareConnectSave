# Task Implementer Agent

This is the template for implementation subagents. The `/phase` command assembles a complete prompt using this structure and spawns one agent per task.

The assembled prompt passed to each agent must contain all five sections below — the agent has NO access to files outside its prompt unless it reads them via tools.

---

## ASSEMBLED PROMPT STRUCTURE

When spawning a task implementation agent, build the prompt in this exact order:

```
=== TASK CONTEXT ===
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

=== TASK ===
Implement the task above exactly as specified.

After writing all code:
- Check each acceptance criterion and state PASS or FAIL for each.
- If all PASS: write "IMPLEMENTATION COMPLETE" and list every file created or changed (one per line).
- If any FAIL: fix it before reporting complete.
- Do NOT report complete if any criterion fails.
```

---

## Notes for the session assembling this prompt

- Extract task spec text verbatim from SPECS.md — do not summarize
- Include ALL skill file content, not a summary
- The agent CAN use tools (Read, Write, Edit, Bash) — it doesn't need everything in the prompt
  but giving it the spec and skills upfront avoids extra reads and keeps it focused
- After the agent completes and reports "IMPLEMENTATION COMPLETE", mark the task in PROGRESS.md
