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

## Step 3 — Check for an in-progress manifest (resume support)

Open `.claude/manifests/$ARGUMENTS.json` if it exists.

**If the file exists and `"status"` is `"in_progress"`:**
- Tell the user: "Found in-progress checkpoint for $ARGUMENTS. Resuming from last known state."
- Read the full manifest content — it contains `files_created`, `criteria_checked`, and `last_note`.
- Set RESUME_MODE = true and keep the manifest data for use in Step 5.

**If the file does not exist, or `"status"` is not `"in_progress"`:**
- Set RESUME_MODE = false.
- Write `.claude/manifests/$ARGUMENTS.json` with this initial content (replace placeholders):
  ```json
  {
    "task_id": "$ARGUMENTS",
    "title": "<title from ticket frontmatter>",
    "status": "in_progress",
    "files_created": [],
    "criteria_checked": {},
    "last_note": "Starting implementation"
  }
  ```

## Step 4 — Load skill files

From the ticket's `skills:` field, read the full content of each `.claude/skills/<skill-name>.md`. This content is injected into the developer agent's prompt.

## Step 5 — Spawn developer agent

Call Agent with a self-contained prompt. The agent has access to Read, Write, Edit, and Bash.

Build the prompt in this order:

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

=== PROGRESS MANIFEST ===
Your progress is checkpointed in `.claude/manifests/{TASK_ID}.json`.

Update this file as you work:
- After creating or modifying each file, add its repo-relative path to the "files_created" array.
- After each acceptance criterion passes, add it to "criteria_checked" with value "PASS".
- After each significant step, update "last_note" with a short description (e.g. "Created 3 of 7 schema files").

This is the resume file — if this session is interrupted, the next run will read it and continue from here.
Never delete this file mid-task. Set "status": "completed" only when all criteria pass.
```

**If RESUME_MODE is true, append this section BEFORE `=== YOUR JOB ===`:**

```
=== RESUME FROM CHECKPOINT ===
This task was interrupted. Do not start from scratch — pick up where the previous agent left off.

Files already on disk (read these first to understand current state, then skip recreating them):
{for each path in manifest.files_created, one path per line}

Acceptance criteria already passing:
{for each key in manifest.criteria_checked with value "PASS", list criterion text}

Last checkpoint note: "{manifest.last_note}"

Start by reading the existing files listed above, then continue with the remaining steps.
Update the manifest as you complete each remaining file or criterion.
```

**Always end the agent prompt with:**

```
=== YOUR JOB ===
Implement everything in the "What to build" and "Technical specification" sections.

When done:
1. Go through each acceptance criterion and state: AC1: PASS/FAIL — reason
2. Fix any FAIL before reporting complete
3. Set "status": "completed" in `.claude/manifests/{TASK_ID}.json`
4. Write "IMPLEMENTATION COMPLETE"
5. List every file created or changed (one per line, with path from project root)
```

## Step 6 — After agent completes

**When agent reports "IMPLEMENTATION COMPLETE":**
1. Change `status: approved` → `status: done` in `.claude/tickets/$ARGUMENTS.md`
2. Change `- [ ] $ARGUMENTS` → `- [x] $ARGUMENTS` in PROGRESS.md
3. Confirm `.claude/manifests/$ARGUMENTS.json` has `"status": "completed"` (agent should have set this; set it yourself if not)
4. Report to user:
   ```
   $ARGUMENTS implementation complete.
   Files changed: [list from agent]

   Next: run /review-task $ARGUMENTS when ready.
   ```

**If agent does NOT report "IMPLEMENTATION COMPLETE" or reports a failure:**
- Show the agent output
- Do NOT update the ticket or PROGRESS.md
- The manifest remains `"in_progress"` with whatever progress was recorded
- Tell the user:
  ```
  Implementation stopped before completion.
  Progress saved to .claude/manifests/$ARGUMENTS.json.
  Run /start-task $ARGUMENTS again to resume from the last checkpoint.
  ```
