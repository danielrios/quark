---
description: "Wrap up the development branch — verify clean, then merge / PR / cleanup."
allowed-tools: [Read, Bash(git *), Bash(./gradlew *), mcp__quarkus-agent__*]
---

Wrap up the current development branch for: $ARGUMENTS

## Skill to use
- `superpowers:finishing-a-development-branch` — verify the tree is clean and the
  gate is green, then present the structured merge / PR / cleanup options.

This runs after `/handoff` (which writes the handoff block and PR draft into
`docs/progress.md`). Do not re-run verification narration here beyond what the
skill requires; honor CLAUDE.md §3 — the quarkus-agent test gate is the
authoritative pass/fail.
