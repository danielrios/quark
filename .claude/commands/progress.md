---
description: Append a structured entry to PROGRESS.md per CLAUDE.md §2 state rule
argument-hint: "<one-line task or status update>"
allowed-tools: [Read, Edit]
---

Append a new entry to `PROGRESS.md` under the "Active Trajectory Logs / Error Traces" section.

The entry must follow this exact shape:

```
### $(date -u +%Y-%m-%d\ %H:%M)Z — $ARGUMENTS
- branch: <current git branch>
- status: <one line — what's done, what's next, or what's blocked>
```

If `$ARGUMENTS` is empty, ask the user for a one-line summary before writing anything. Never invent task content. After editing, show the diff of just the appended lines so the user can confirm.

This is the only correct way to record in-flight state per CLAUDE.md §2 ("If a file change or intent is not committed to Git or written to PROGRESS.md, it does not exist.").
