---
description: "Create an ordered implementation plan from a spec. Opus-tier: dependency-aware decomposition."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(cat *), Bash(find *), Bash(ls *)]
---

Read these project files first:
- CLAUDE.md (binding contract — especially §3 test gate, §4 failure escalation)
- The spec referenced in $ARGUMENTS (or the latest one in `docs/superpowers/specs/`)
- Existing plans in `docs/superpowers/plans/` for format reference
- docs/progress.md for current project state

## Your task

Create an ordered implementation plan for: $ARGUMENTS

Create the plan at: `docs/superpowers/plans/<date>-<slug>.md`

Follow the format of existing plans. Each task must have:
- `- [ ]` checkbox (for tracking completion)
- Clear, testable definition of done
- Reference to the test gate (CLAUDE.md §3)
- Estimated complexity: `(S)` / `(M)` / `(L)`

## Structure
1. **Task 0: Scaffolding** (if needed) — create files, directories, skeleton classes
2. **Task 1..N: Implementation** — ordered by dependency
3. **Task N+1: Integration test** — end-to-end verification
4. **Task N+2: Documentation** — update ARCHITECTURE.md, progress.md, any ADRs

## Rules
- Tasks must be ordered by dependency (no forward references)
- Each task must be completable in ONE Claude Code session
- Follow WIP=1 — the implementer will do one task at a time
- Include file paths and class names where possible
- `git add` and `git commit` the plan document
