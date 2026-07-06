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

### Step 3 — Stage all changes

```
git add -A
```

### Step 4 — Commit with standard message

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

### Step 5 — Push

```
git push origin main
```

### Step 6 — Report

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
