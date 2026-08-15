Review the implementation of task $ARGUMENTS and auto-fix any blocking issues.

## Step 1 — Load the ticket

Open `.claude/tickets/$ARGUMENTS.md`.

- If not found → say "No ticket found for $ARGUMENTS." Stop.
- If `status: draft` or `status: approved` → say "Task hasn't been implemented yet. Run `/start-task $ARGUMENTS` first." Stop.
- Read the full ticket content — this is the contract both the reviewer and fixer use.

## Step 2 — Load skill files (in this session)

From the ticket's `skills:` field, read the full content of each `.claude/skills/<skill-name>.md`.

## Step 3 — Spawn review agent

Read `.claude/agents/reviewer.md` for the full agent prompt. Call Agent with that content, substituting:
- `{TASK_ID}` → the task ID from $ARGUMENTS
- Ticket content → replace `{full content of .claude/tickets/{TASK_ID}.md — paste verbatim}` with the full ticket content read in Step 1
- Skill content → for each skill in the ticket's `skills:` field, replace the skill placeholder block with the full content of `.claude/skills/<skill>.md`

## Step 4 — Handle verdict

### APPROVED or APPROVED_WITH_MINOR_ISSUES:
Extract the `=== MANUAL QA STEPS ===` section from the review agent's output (present
whenever the verdict is APPROVED or APPROVED_WITH_MINOR_ISSUES) and write it verbatim to
`.claude/qa/$ARGUMENTS.qa.md` (create the `.claude/qa/` folder if it doesn't exist yet).
If that file already exists from a prior review pass on this ticket, overwrite it — the
checklist should always reflect the latest reviewed code.

Report verdict, minor items, and the QA checklist location to user. Done.

### NEEDS_REWORK:
Extract the numbered issue list. Spawn fix agent (cycle 1):

Read `.claude/agents/fixer.md` for the full agent prompt. Call Agent with that content, substituting:
- `{TASK_ID}` → the task ID from $ARGUMENTS
- `{ticket title}` → title from the ticket's frontmatter
- Ticket content → full content of `.claude/tickets/{TASK_ID}.md`
- Skill content → same skill files used in Step 2
- `{numbered issue list from the reviewer — verbatim}` → the numbered issue list from the reviewer's NEEDS_REWORK output, verbatim

After fix agent reports "FIX COMPLETE" → spawn another review agent (same prompt as Step 3).

**Maximum 3 review → fix cycles.** If still NEEDS_REWORK after cycle 3, stop and report to user: "Still failing after 3 fix attempts. Remaining issues: [list]. Manual intervention needed."

## Step 5 — Report outcome

On APPROVED: "$ARGUMENTS review passed (after N cycle(s)). Manual QA checklist written to `.claude/qa/$ARGUMENTS.qa.md` — run through it before `/push $ARGUMENTS`. Ready to move to next task."
On manual intervention: show remaining issues, ask how to proceed.
