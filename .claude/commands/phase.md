Create all work tickets for Phase $ARGUMENTS.

Tickets are markdown files you review before any code is written.
After reviewing, you run `/start-task T001` for each task individually.

## Step 1 — Read the phase manifest

Open `.claude/phases/phase-$ARGUMENTS.md`. Get the ordered task list and goal.

If the file doesn't exist → say "Phase $ARGUMENTS not found. Valid phases: 0–14." Stop.

## Step 2 — Skip tasks that already have tickets

For each task in the phase, check if `.claude/tickets/{TASK_ID}.md` already exists.
- If it does: skip that task (don't re-create)
- If all tasks already have tickets: say "All tickets for Phase $ARGUMENTS already exist in `.claude/tickets/`." Stop.

## Step 3 — Create tickets for missing tasks

For each task without a ticket, run this sequence:

**a) Gather source material in this session:**
- Extract the full task section from SPECS.md (verbatim, from `### TASK_ID` to the next `---`)
- Find the relevant section in REQUIREMENTS.md that this task implements
- Get the skill names for this task from `.claude/skill-map.md` (names only, not file contents)
- Get the phase goal from `.claude/phases/phase-$ARGUMENTS.md`

**b) Spawn a ticket creator agent** with:
```
=== YOUR ROLE ===
You are a technical PM creating a work ticket for ShareConnectSave.
This ticket will be reviewed by the Tech Lead before any code is written.
Write it so a developer agent can implement without reading any other files.
Write it so the Tech Lead understands the business value at a glance.

=== SOURCE MATERIAL ===
Task ID: {TASK_ID}

--- TASK SPEC (from SPECS.md, verbatim) ---
{full task spec text}

--- BUSINESS CONTEXT (from REQUIREMENTS.md) ---
{relevant requirements section}

--- PHASE GOAL ---
{phase manifest goal statement}

--- SKILLS REQUIRED (names only) ---
{skill names, or "none"}

=== OUTPUT ===
Create the file: .claude/tickets/{TASK_ID}.md

Use this exact structure:

---
id: {TASK_ID}
title: {task title}
phase: {phase number}
service: {service}
language: {Java | .NET | Angular | YAML | SQL | N/A}
skills: [{skill names}]
depends: [{dependency task IDs or empty}]
status: draft
---

# {TASK_ID} — {Task Title}

## Why this ticket exists
One paragraph. What breaks or stays missing without this ticket.
Plain English — no jargon.

## What to build
Bullet points. Specific names: class names, endpoint paths, table names, topic names.

## Technical specification
{paste Spec section from SPECS.md verbatim}

## Acceptance criteria
{paste Acceptance Criteria from SPECS.md verbatim as a checklist}
- [ ] ...

## Patterns demonstrated
Each design pattern or SOLID/system design principle this ticket teaches.
Format: **Pattern name** — one sentence on what it demonstrates.

## Agent implementation notes
Specific guidance for the developer:
- Key files to create (paths from project root)
- Patterns to use (with reference to skill file section names)
- What NOT to do (common mistakes for this task type)
```

Run tasks sequentially. Show progress: "Creating T001... done. Creating T002... done."

## Step 4 — After all tickets created

Report the full list:
```
Phase $ARGUMENTS tickets ready for review:

  T001  [title]  .claude/tickets/T001.md
  T002  [title]  .claude/tickets/T002.md
  ...

Your next steps:
1. Open each ticket in your IDE and review the requirement
2. For tickets you approve: change  status: draft  →  status: approved
3. Run /start-task T001  to implement one ticket at a time
4. Run /status  to see overall progress at any point
```
