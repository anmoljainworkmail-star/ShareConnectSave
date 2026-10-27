Create or update a boxes-and-arrows workflow diagram for task $ARGUMENTS — no prose, one-liners only.

## Purpose

Every deep-dive "how does this actually flow through the code" discussion (JWT validation,
rate limiting, etc.) should be distillable into one diagram that can be understood at a
glance later, without re-reading the original conversation. This command is what produces
that diagram — it is a quick-glance reference, not documentation.

## Steps

1. Read `.claude/tickets/$ARGUMENTS.md` for what this task builds, and read the actual
   implementation files it touched (check `.claude/manifests/$ARGUMENTS.json`'s
   `files_created` for the exact list). The diagram must reflect the real code, not the
   ticket's original plan — implementation details (file names, middleware order, decision
   points) may have changed during implementation or review.

2. Build a Mermaid `flowchart TD` (or `LR`, whichever reads better for this specific flow)
   that traces one representative request/execution through the system end to end:
   - One node per meaningful step (a middleware, a decision, a class/method boundary) —
     each node label is a few words, never a sentence.
   - Decision points (branches: public vs protected route, permit available vs exhausted,
     valid vs invalid token) are diamond nodes with the outgoing arrows labeled with the
     actual condition (e.g. "permit left" / "exhausted"), not generic Yes/No when a more
     specific label reads faster.
   - Terminal outcomes (forwarded downstream, 401, 429, etc.) are distinct end nodes.
   - No paragraph explanations inside the diagram. If a node needs more than ~5 words,
     split it into two nodes instead of writing a long label.

3. Write (or replace, if it already exists) one file in the `workflows/` folder at the
   project root — create the folder if it doesn't exist yet:
   - Ticket-scoped diagram: `workflows/T0XX-kebab-case-title.md`
   - Cross-cutting diagram not owned by one ticket (e.g. an env/secrets resolution path
     spanning several tickets): `workflows/kebab-case-topic-flow.md`

4. Each file contains, in order:
   - A `# T0XX — <title>` (or `# <Title>`) header.
   - One short line: `Quick-glance boxes-and-arrows reference for <what it traces>. No
     prose — if you need the "why," see <ticket file(s) or other reference>. Generated/
     updated via \`/diagram-task\`.`
   - The Mermaid diagram (`flowchart TD`/`LR`; use `subgraph` blocks to separate distinct
     actors/processes/services when that reads clearer than one flat graph).
   - At most one line directly under the diagram if there's a single fact that doesn't fit
     any box (e.g. "GlobalLimiter always runs in parallel with the named policy, not
     instead of it") — skip this line entirely if the diagram is self-explanatory.

5. Do not duplicate this in `GLOSSARY.md` or `README.md`'s "Concepts I can explain cold"
   section — those already own syntax-level and pattern-level explanations respectively.
   `workflows/` owns *flow* only: what calls what, in what order, and where it branches.

## What NOT to do

- No architecture prose, no "why this pattern was chosen" — that's `/explain-task`'s job.
- No per-node paragraph descriptions — a node is a label, not a summary.
- Don't diagram every ticket automatically — only run this when explicitly invoked for a
  specific task ID.
