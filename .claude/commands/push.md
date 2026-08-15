Commit and push changes for task $ARGUMENTS, or push without committing if no task ID given.

## Case A — Task ID provided (e.g. `/push T001`)

### Step 1 — Read the ticket

Open `.claude/tickets/$ARGUMENTS.md`.

- If not found → say "No ticket found for $ARGUMENTS." Stop.
- If `status: draft` or `status: approved` → say "Task hasn't been implemented yet. Run `/start-task $ARGUMENTS` first." Stop.
- Read: `title`, `phase`, `service` fields.

### Step 2 — Check there is something to commit

Run:
```
git status --short
```

If output is empty → say "Nothing to commit — working tree is clean." Stop.

### Step 3 — Update README.md narrative

Open `README.md` at the repo root. This is a spoken-explanation narrative for interview recall — NOT a ticket log. There must never be a "T00X —" heading or one card per ticket. New work gets woven into the prose as if continuing a story out loud.

- Get today's date first (e.g. `date +"%b %-d, %Y"` in Bash, or `Get-Date -Format "MMM d, yyyy"` in PowerShell). Every new paragraph in "The story so far" starts with that date in bold, e.g. `**Aug 15, 2026 — ...**`, exactly like the existing entries — this is what lets the narrative double as a timeline. No ticket IDs anywhere in the prose.
- **Phase heading** — "The story so far" is broken into `### Phase {N} — {phase name}` subheadings (from `PROGRESS.md`), each containing that phase's dated paragraphs. If this ticket's phase already has a heading, add the new dated paragraph under it. If this ticket starts a new phase, add a new `### Phase {N} — {name}` heading after the previous phase's paragraphs, then the new dated paragraph under it.
- **"The story so far"** — add one new dated paragraph (or extend the last paragraph, keeping its existing date, if this ticket is a small continuation of the same idea) describing, in plain spoken English, what was just built and why it was necessary at this point in the story. Follow the existing voice: "Then we...", "Next we...", concrete nouns. Explain the reasoning, not just the action — what problem this solves, what would break without it.
- **"Where that leaves things right now"** (the closing paragraph of the whole section, after the last phase heading) — rewrite it to reflect the new current state, and update its `(as of {date})` marker to today's date. What's now done, what's immediately next per `PROGRESS.md`. This paragraph gets replaced each time, not appended to.
- **TL;DR** — this is the 3-sentence cold-open pitch and should stay stable. Only touch it if this ticket changes the current phase number ("Currently early — Phase {N}...") or fundamentally changes what the platform does — never rewrite it just because a ticket landed.
- **"Concepts I can explain cold because of this"** — if this ticket's "Patterns demonstrated" introduces a genuinely new concept not already bulleted here, add one bullet in the same voice (concept name bolded, then a plain-English explanation of what it means and why it's used — not copy-pasted from the ticket). If the concept already has a bullet, leave it alone.
- Do NOT create per-ticket sections or a separate changelog table. If a paragraph is getting long, tighten the prose rather than splitting it into a list — this stays a narrative someone could read aloud.

### Step 4 — Stage all changes

```
git add -A
```

### Step 5 — Commit with standard message

Construct the commit message:
```
{TASK_ID}: {ticket title} — {one-line note on what concept it demonstrates}
```

The teaching note comes from the "Patterns demonstrated" section of the ticket — pick the most important pattern in one clause (not a full sentence).

Examples:
- `T001: Monorepo scaffold — service folders + Docker Compose stubs`
- `T004: Docker Compose infrastructure — full local dev stack with health checks`
- `T020: User Service auth endpoints — JWT issued once at gateway, never inside service`

Run:
```
git commit -m "{constructed message}"
```

### Step 6 — Push

```
git push origin main
```

### Step 7 — Report

```
Pushed: {TASK_ID} — {commit message}
Branch: main
Files committed: {count from git show --stat HEAD}
```

---

## Case B — No argument (just `/push`)

Push whatever is already committed — no new commit.

### Step 1 — Check for unpushed commits

```
git log origin/main..HEAD --oneline
```

If empty → say "Nothing to push — already up to date." Stop.

Show the list of commits that will be pushed.

### Step 2 — Push

```
git push origin main
```

### Step 3 — Report

```
Pushed {N} commit(s) to origin/main:
  {commit list}
```

---

## Error handling

- If `git push` fails due to upstream divergence → say "Push rejected — remote has changes not in your local branch. Run `git pull --rebase origin main` first, then re-run `/push`."
- If `git commit` fails → show the full error output and stop.
