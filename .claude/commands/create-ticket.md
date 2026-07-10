Create a work ticket for task $ARGUMENTS.

## Step 1 — Check if ticket already exists

Look for `.claude/tickets/$ARGUMENTS.md`. If it exists:
- If status is `draft` → say "Ticket exists but not yet approved. Open `.claude/tickets/$ARGUMENTS.md` to review it."
- If status is `approved` → say "Ticket already approved. Run `/start-task $ARGUMENTS` to implement it."
- If status is `done` → say "Ticket is already done. Run `/review-task $ARGUMENTS` if you want to re-review."
- Stop in all cases.

## Step 2 — Gather source material (in this session)

Read all four sources before spawning the agent:

**a) Task spec from SPECS.md:**
Find the full section for "$ARGUMENTS" (from `### $ARGUMENTS` to the next `---`). Extract verbatim: Service, Language, Depends On, Spec, Acceptance Criteria.

**b) Business context from REQUIREMENTS.md:**
Based on the service and feature this task belongs to, find the relevant functional requirement section. For example, T016 (Google OAuth) → section "1. Authentication & Onboarding". Extract the relevant paragraphs.

**c) Phase goal from `.claude/phases/`:**
Find which phase this task belongs to (use `.claude/skill-map.md` or SPECS.md phase header). Read the corresponding `phase-N.md` for the goal statement.

**d) Skills needed:**
Open `.claude/skill-map.md` and find "$ARGUMENTS". Note the skill names (do NOT read the skill file content — just the names).

## Step 3 — Spawn ticket creator agent

Read `.claude/agents/ticket-creator.md` for the full agent prompt. Call Agent with that content, substituting:
- `{TASK_ID}` → the task ID from $ARGUMENTS
- `{full task spec text}` → verbatim spec section from Step 2a
- `{relevant requirements section}` → business context from Step 2b
- `{phase-N.md goal statement}` → phase goal from Step 2c
- `{comma-separated skill names, or "none"}` → skill names from Step 2d

## Step 4 — After ticket is created

Report to the user:
"Ticket created: .claude/tickets/$ARGUMENTS.md

Review the ticket in your IDE. When satisfied:
1. Change `status: draft` → `status: approved` in the file
2. Run `/start-task $ARGUMENTS` to begin implementation"
