Explain task $ARGUMENTS from SPECS.md in depth — for learning, not just implementation.

## What to cover

1. **What this task builds** — one sentence, plain English.

2. **Why this task exists** — the real-world problem it solves. E.g., "Without this, X would happen."

3. **The pattern(s) used** — for each design pattern, architecture principle, or library involved:
   - Name the pattern/principle
   - Explain it in plain English (assume no prior knowledge of the term)
   - Show the Problem → Why it matters → How this solves it → Example from this task structure
   - Reference where else in the project this same pattern appears

4. **Key technical decisions** — decisions made in the spec and the reasoning behind each. Call out any trade-offs.

5. **What could go wrong** — the failure modes this task guards against, and how the implementation handles them.

6. **How it connects** — which other tasks/services this task directly affects, and what Kafka events or HTTP calls cross the boundary.

Keep explanations plain-English first, code/jargon second. This is a learning project — go deeper than a production code review would.
