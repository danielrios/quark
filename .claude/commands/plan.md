---
description: "Turn a spec into an ordered, task-by-task implementation plan. Opus-tier: dependency-aware decomposition."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(cat *), Bash(find *), Bash(ls *), Bash(grep *), Bash(rg *), mcp__quarkus-agent__*, mcp__context7__*]
---

Write an implementation plan from the spec: $ARGUMENTS

Read first: CLAUDE.md, the spec in $ARGUMENTS, the relevant ADRs (do not skip
ahead of ADR 0003's sequencing), and existing plans in `docs/superpowers/plans/`.

## Skills to use
- `superpowers:writing-plans` — produce an ordered, dependency-aware plan with a
  checkbox per task.
- `codebase-design` — where the plan adds a seam, keep modules deep so the
  ADR 0003 refactor phase can extract them cleanly (CLAUDE.md §7).

## Output contract (the implement phase depends on this)
Write the plan to `docs/superpowers/plans/<date>-<slug>.md`. Every task MUST be a
markdown checkbox (`- [ ]`) so `/implement` can track completion and the
orchestrator's termination contract works. `git add` + `git commit` the plan.
