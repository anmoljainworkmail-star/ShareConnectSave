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

Read `.claude/agents/task-implementer.md` for the full agent prompt. Call Agent with that content, substituting:
- `{TASK_ID}` → the task ID from $ARGUMENTS
- Ticket content → replace `{full content of .claude/tickets/{TASK_ID}.md — paste verbatim}` with the full ticket content read in Step 1
- Skill content → for each skill in the ticket's `skills:` field, replace the skill placeholder block with the full content of `.claude/skills/<skill>.md`
- `{RESUME_SECTION}` → if RESUME_MODE is true, use the **RESUME SECTION TEMPLATE** at the bottom of the agent file, substituting `files_created`, `criteria_checked`, and `last_note` from the manifest; if RESUME_MODE is false, remove the `{RESUME_SECTION}` placeholder entirely

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
