Implement task $ARGUMENTS using its approved ticket.

## Step 1 — Load and validate the ticket

Open `.claude/tickets/$ARGUMENTS.md`.

- If the file does not exist → say "No ticket found. Run `/create-ticket $ARGUMENTS` first." Stop.
- If `status: draft` → say "Ticket is still in draft. Review `.claude/tickets/$ARGUMENTS.md`, change status to `approved`, then re-run." Stop.
- If `status: done` → say "Task already implemented. Run `/review-task $ARGUMENTS` to verify." Stop.
- If `status: approved` → continue.

Read the full ticket content — this is what the developer agent will receive.

## Step 2 — Check dependencies

From the ticket's `depends:` field, open PROGRESS.md and verify each dependency task is marked `[x]`. If any are `[ ]`, stop and list them: "Cannot start — waiting on: T00X, T00Y".

## Step 3 — Load skill files

From the ticket's `skills:` field, read the full content of each `.claude/skills/<skill-name>.md`. This content is injected into the developer agent's prompt.

## Step 4 — Spawn developer agent

Call Agent with a self-contained prompt. The agent has access to Read, Write, Edit, and Bash.

```
=== YOUR ROLE ===
You are a developer implementing a work ticket for ShareConnectSave.
This is a learning project. Add short comments above non-obvious code naming the 
pattern being demonstrated — not what the code does, but what principle it applies and why.

=== TICKET ===
{full content of .claude/tickets/{TASK_ID}.md — paste verbatim}

=== SKILL REFERENCE ===
{For each skill listed in the ticket's skills: field, paste the full skill file content}

--- SKILL: {skill-name} ---
{content}

=== ARCHITECTURE RULES (non-negotiable) ===
1. OUTBOX PATTERN — Kafka publishing goes through outbox table in same DB transaction. Never call KafkaTemplate or IProducer directly from business code.
2. CONSUMER IDEMPOTENCY — Every Kafka consumer checks processed_events before acting.
3. NO HARDCODED CONFIG — All DB URLs, ports, keys, TTLs from environment variables.
4. GUARD CLAUSES — Early return over nested ifs.
5. JWT IDENTITY — Read X-User-Id / X-User-Role / X-User-Gender from headers only. Never decode JWT inside a service.
6. ERROR ENVELOPE — { "code": "...", "message": "...", "traceId": "..." } on all errors.
7. ANGULAR — NgModule only. No standalone components, directives, or pipes.
8. COMMENTS — Name the pattern/principle, not what the code does.

=== YOUR JOB ===
Implement everything in the "What to build" and "Technical specification" sections.

When done:
1. Go through each acceptance criterion and state: AC1: PASS/FAIL — reason
2. Fix any FAIL before reporting complete
3. Write "IMPLEMENTATION COMPLETE"
4. List every file created or changed (one per line, with path from project root)
```

## Step 5 — After agent completes

When agent reports "IMPLEMENTATION COMPLETE":
1. Update the ticket: change `status: approved` → `status: done` in `.claude/tickets/$ARGUMENTS.md`
2. Update PROGRESS.md: change `- [ ] $ARGUMENTS` → `- [x] $ARGUMENTS`
3. Report to user:
   "$ARGUMENTS implementation complete.
   Files changed: [list from agent]
   
   Next: Review the code in your IDE, then run `/review-task $ARGUMENTS` when ready."

If agent does NOT report "IMPLEMENTATION COMPLETE" or reports a failure:
- Show the full agent output
- Do NOT update the ticket or PROGRESS.md
- Ask the user how to proceed
