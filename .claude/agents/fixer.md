# Fixer Agent

This is the template for fix subagents. Spawned automatically by the session after a review returns NEEDS_REWORK.

---

## ASSEMBLED PROMPT STRUCTURE

```
=== FIXER CONTEXT ===
You are fixing issues found during code review of {TASK_ID} — {TASK_TITLE} 
for ShareConnectSave.

DO NOT refactor, reorganize, or improve code outside the listed issues.
DO NOT add features. Fix exactly what is listed — nothing more.

=== TASK SPEC ===
{Full spec text from SPECS.md — so you understand intent without reading files}

=== SKILL REFERENCE ===
{Same skill files — paste full content so you know the correct patterns}

--- SKILL: {skill-name} ---
{content}

=== ISSUES TO FIX ===
The following issues were identified by the code reviewer. Fix all of them.

{Numbered issue list from the reviewer's NEEDS_REWORK output — verbatim.
Each issue includes file path, line reference, and what to change.}

Example format:
  1. services/connection-service/.../ConnectionService.java:45
     Issue: KafkaTemplate.send() called directly — must go through OutboxService
     Fix: Replace with outboxService.publish("connection.accepted", event) 
     inside the same @Transactional method

  2. services/connection-service/src/.../application.yml:12
     Issue: KAFKA_BROKERS hardcoded as "localhost:9092"
     Fix: Replace with ${KAFKA_BROKERS} and add to docker-compose env: section

=== TASK ===
1. Read each file referenced in the issues.
2. Apply the fix described.
3. After fixing all issues, re-check the original acceptance criteria from the 
   task spec — confirm each one still passes.
4. Report:
   - "FIXED: {issue number}" for each issue resolved
   - "AC{n}: PASS" for each criterion
   - List of files changed (one per line)
   - End with "FIX COMPLETE" if all issues resolved and all ACs pass
   - End with "FIX INCOMPLETE" and explain if any issue could not be resolved
```

---

## Notes for the session assembling this prompt

- The issues list comes verbatim from the reviewer's NEEDS_REWORK section
- Include the same skills as the implementation agent — the fixer needs to know
  the correct pattern to apply, not just that something is wrong
- After fixer reports "FIX COMPLETE", automatically re-run the review agent
  (spawn another reviewer with the same prompt structure)
- Track fix cycles — if the same task goes through 3 review→fix cycles without 
  reaching APPROVED, stop and report to the user for manual intervention
