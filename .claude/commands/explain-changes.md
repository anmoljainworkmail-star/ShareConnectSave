Explain the actual code that was just written for task $ARGUMENTS (or the current uncommitted diff if no task ID is given) — for a reader who has never seen this code, this pattern, or this concept before. Different from `/explain-task`: that one explains the *ticket/spec* before code exists; this one explains the *real code* that exists right now, grounded in the actual files.

## Step 1 — Find what changed

**Case A — task ID given (e.g. `/explain-changes T003`):**
- Open `.claude/manifests/$ARGUMENTS.json`. If it exists, use its `files_created` array as the file list.
- If no manifest, open `.claude/tickets/$ARGUMENTS.md` — if `status: draft` or `status: approved` (not yet implemented) → say "T00X hasn't been implemented yet. Run `/start-task $ARGUMENTS` first." Stop.
- If neither manifest nor ticket exists → say "No record of $ARGUMENTS. Nothing to explain." Stop.

**Case B — no argument:**
- Run `git status --short`. If non-empty, that's the file list; use `git diff HEAD -- <files>` for the actual diffs.
- If empty, use `git show --stat HEAD` for the most recent commit's file list instead.
- If there is truly nothing (clean tree, no commits) → say "No pending changes and no task ID given — nothing to explain." Stop.

## Step 2 — Read everything before writing anything

Read the full current content of every file in the list — not just the diff hunks. A reader with zero context needs the surrounding code (imports, class structure, what calls this) to make sense of what changed, not an isolated fragment.

If a task ID was given, also skim the ticket's "Patterns demonstrated" section — it names the intended concepts, but you must verify each one is actually present in the code, not just assumed from the ticket text.

## Step 3 — Write the explanation

Follow this exact structure. Plain English always comes before jargon — assume the reader has never heard of any technical term used, even ones that may have come up in earlier conversation.

### 3.1 — One-paragraph plain summary
What this code does and why it needed to be written, in plain sentences a non-programmer could follow. No jargon, no pattern names yet.

### 3.2 — Concepts, one at a time
For every pattern, protocol, library feature, or architectural idea the code actually uses (not every concept that could theoretically apply):
1. Name it.
2. Give a real-world analogy or a concrete scenario from ShareConnectSave *before* any code or jargon.
3. Then walk: **The Problem** (what goes wrong without this) → **Why it matters** (concretely, not abstractly) → **How this code solves it** → **Where you can see it** (exact file:line from the real files you read in Step 2).
4. If this same concept appears elsewhere in the codebase, name that file too — but the explanation must stand on its own without requiring the reader to go look at it.

Never assume the reader already knows a term — even ones like "idempotency," "saga," "circuit breaker," "two-phase commit," "event sourcing," "polyglot," "DTO," "middleware" — explain each from zero, every time this command runs.

### 3.3 — Walk the actual code
File by file, block by block, in the order a reader would naturally trace through it (entry point first). For each block:
- Plain English first: what is happening here, in a sentence.
- Then the why: what would break, or what would be worse, if this block were written the naive/obvious way instead.
- Quote only the specific lines being discussed (with file:line references), never paste a whole file.

### 3.4 — What and why (recap)
3-5 bullets. Each bullet: what was built, and the one-sentence reason it exists. This is the "if you only remember five things" summary.

### 3.5 — Check yourself
2-3 questions the reader should now be able to answer if the explanation worked, e.g. "Why does the Java record need no annotations while the C# record needs `[JsonPropertyName]` on every field?" Don't answer them — they're for the reader to self-test. Pick questions that specifically probe the *why*, not the *what* (a reader who only skimmed the code could still answer a what-question).

## Step 4 — Do not modify anything

This command is read-only. Never edit the ticket, manifest, or PROGRESS.md — that's `/start-task` and `/review-task`'s job. This command only explains.
