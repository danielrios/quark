---
description: "Write a formal specification from a brainstorm. Opus-tier: rigorous requirement definition."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(cat *), Bash(find *), Bash(ls *)]
---

Read these project files first:
- CLAUDE.md (binding contract)
- ARCHITECTURE.md
- The brainstorm document referenced in $ARGUMENTS (or the latest one in `docs/superpowers/brainstorms/`)
- Existing specs in `docs/superpowers/specs/` for format reference
- `docs/agents/domain.md` — the domain-doc consumer rules and `CONTEXT.md` vocabulary

## Skills to use
- `domain-modeling` — define every concept in the spec using the project's
  ubiquitous language; if a needed term isn't in `CONTEXT.md`, that's a gap to
  record, not a synonym to invent.
- `grilling` — before finalizing, interrogate the spec's open questions and
  assumptions so they surface here rather than mid-implementation.

## Your task

Write a formal specification based on the brainstorm for: $ARGUMENTS

Create the spec at: `docs/superpowers/specs/<date>-<slug>.md`

Follow the format of existing specs. Include:
1. **Goal** — one sentence
2. **Non-goals** — what this explicitly does NOT do
3. **Design** — detailed technical approach (classes, packages, interactions)
4. **API surface / interfaces** — public contracts
5. **Test strategy** — what tests prove it works, referencing CLAUDE.md §3 gate
6. **Rollback plan** — how to undo if something goes wrong
7. **Open questions** — anything that needs human input

## Rules
- Follow CLAUDE.md §6 (YAGNI/KISS) — spec the simplest solution
- Follow CLAUDE.md §7 — shape code so ADR 0003 refactor phase can extract seams
- Do NOT implement anything — spec only
- `git add` and `git commit` the spec document
