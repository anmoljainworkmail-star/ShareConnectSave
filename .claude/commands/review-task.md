Review the implementation of task $ARGUMENTS and auto-fix any blocking issues.

## Step 1 — Load the ticket

Open `.claude/tickets/$ARGUMENTS.md`.

- If not found → say "No ticket found for $ARGUMENTS." Stop.
- If `status: draft` or `status: approved` → say "Task hasn't been implemented yet. Run `/start-task $ARGUMENTS` first." Stop.
- Read the full ticket content — this is the contract both the reviewer and fixer use.

## Step 2 — Load skill files (in this session)

From the ticket's `skills:` field, read the full content of each `.claude/skills/<skill-name>.md`.

## Step 3 — Spawn review agent

Call Agent with:

```
=== YOUR ROLE ===
You are a senior code reviewer for ShareConnectSave.
Your findings must explain WHY each issue matters — not just flag the code.
This is a learning project: every finding is a teaching opportunity.

=== TICKET ===
{full content of .claude/tickets/{TASK_ID}.md — paste verbatim}

=== SKILL REFERENCE ===
{full skill file content for each required skill}

--- SKILL: {name} ---
{content}

=== WHAT TO REVIEW ===

**1. Acceptance Criteria**
For each criterion in the ticket:
  AC1: PASS / FAIL / PARTIAL
  Evidence: <file:line>
  Why it matters if failing: <consequence>

**2. Architecture Rules**
Check each — give file:line for any violation:
  □ Outbox: Kafka publishing goes through outbox (never KafkaTemplate/IProducer directly)
  □ Outbox: write is in same @Transactional / SaveChangesAsync as business write
  □ Idempotency: every Kafka consumer checks processed_events before acting
  □ No hardcoded config (all env vars)
  □ JWT identity from X-User headers only (no token decode inside service)
  □ Error envelope { code, message, traceId } on all error paths
  □ Angular: all components in NgModule (no standalone)
  □ Java: Lombok, MapStruct, WebClient (no hand-written mappers, no RestTemplate)
  □ .NET: repos injected as interfaces (Dependency Inversion)
  □ Guard clauses over nested ifs

**3. SOLID Principles**
For each principle that applies to this task's code:
  [S/O/L/I/D]: FOLLOWED / VIOLATED
  File:line if violated
  Consequence: what breaks or becomes harder to change

**4. Security**
Flag any of these (all are blockers):
  - Hardcoded secrets or API keys in code
  - Missing input validation at HTTP entry points
  - SQL built from string concat (injection risk)
  - Sensitive data (phone, gender, location) in logs or error responses
  - Endpoint that doesn't verify identity before acting on data

**5. Learning Insights**
Pick 1-3 decisions in the code worth understanding. For each:
  Concept: <name>
  Why it matters: <plain English, one paragraph>
  What breaks without it: <specific failure scenario>

=== VERDICT ===
End with exactly one of:
  VERDICT: APPROVED
  VERDICT: APPROVED_WITH_MINOR_ISSUES — [list minor items inline]
  VERDICT: NEEDS_REWORK

If NEEDS_REWORK, follow with a numbered issue list. Each issue must include:
  - File path and line number
  - What the problem is
  - Exactly what to change
  
This list is passed verbatim to the fix agent.
```

## Step 4 — Handle verdict

### APPROVED or APPROVED_WITH_MINOR_ISSUES:
Report verdict and minor items to user. Done.

### NEEDS_REWORK:
Extract the numbered issue list. Spawn fix agent (cycle 1):

```
=== YOUR ROLE ===
You are fixing code review issues in {TASK_ID} — {ticket title}.
Fix ONLY the listed issues. Do not refactor, reorganize, or add anything new.

=== TICKET ===
{full ticket content — so you understand the intent}

=== SKILL REFERENCE ===
{same skill files — so you apply the correct pattern}

=== ISSUES TO FIX ===
{numbered issue list from the reviewer — verbatim}

=== YOUR JOB ===
1. Read each file referenced in the issues
2. Apply the exact fix described
3. After all fixes, re-verify each acceptance criterion from the ticket
4. Report:
   - FIXED: {issue number} for each resolved issue
   - AC{n}: PASS for each criterion
   - List files changed
   - End with "FIX COMPLETE" if done, or "FIX INCOMPLETE: [reason]" if not
```

After fix agent reports "FIX COMPLETE" → spawn another review agent (same prompt as Step 3).

**Maximum 3 review → fix cycles.** If still NEEDS_REWORK after cycle 3, stop and report to user: "Still failing after 3 fix attempts. Remaining issues: [list]. Manual intervention needed."

## Step 5 — Report outcome

On APPROVED: "$ARGUMENTS review passed (after N cycle(s)). Ready to move to next task."
On manual intervention: show remaining issues, ask how to proceed.
