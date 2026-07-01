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

Read `.claude/agents/ticket-creator.md` for the output format. Then call Agent with:

```
=== YOUR ROLE ===
You are a technical PM creating a work ticket for ShareConnectSave.
Your output is reviewed by the Tech Lead before any code is written.
Write so a developer can implement without reading other files.
Write so the Tech Lead understands the business value at a glance.

=== SOURCE MATERIAL ===
Task ID: {TASK_ID}

--- TASK SPEC (from SPECS.md, verbatim) ---
{full task spec text}

--- BUSINESS CONTEXT (from REQUIREMENTS.md) ---
{relevant requirements section}

--- PHASE GOAL ---
{phase-N.md goal statement}

--- SKILLS REQUIRED ---
{comma-separated skill names, or "none"}

=== OUTPUT ===
Create the file: .claude/tickets/{TASK_ID}.md

Use exactly the template from the ticket-creator agent format:
- frontmatter with status: draft
- ## Why this ticket exists  (business context paragraph)
- ## What to build  (concrete deliverable bullets)
- ## Technical specification  (verbatim from SPECS.md)
- ## Acceptance criteria  (verbatim as checklist)
- ## Patterns demonstrated  (patterns + one-line teaching note each)
- ## Agent implementation notes  (file paths, patterns to use, what NOT to do)
```

## Step 4 — After ticket is created

Report to the user:
"Ticket created: .claude/tickets/$ARGUMENTS.md

Review the ticket in your IDE. When satisfied:
1. Change `status: draft` → `status: approved` in the file
2. Run `/start-task $ARGUMENTS` to begin implementation"
