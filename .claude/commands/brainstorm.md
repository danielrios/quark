---
description: "Explore the problem and define an approach before any code. Opus-tier: creative trade-off exploration."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(cat *), Bash(find *), Bash(ls *), Bash(grep *), Bash(rg *), mcp__quarkus-agent__*, mcp__context7__*]
---

Brainstorm the approach for: $ARGUMENTS

Read first: CLAUDE.md, ARCHITECTURE.md, the relevant ADRs, and existing
brainstorms in `docs/superpowers/brainstorms/` for format.

## Skills to use
- `superpowers:brainstorming` — explore intent, requirements, and design options.
- `domain-modeling` + `docs/agents/domain.md` — name concepts using the project's
  vocabulary (`CONTEXT.md`); flag any term that isn't in the glossary yet.
- `grilling` — stress-test the leading option before committing to it.

## Output contract (the spec phase depends on this)
Write the brainstorm to `docs/superpowers/brainstorms/<date>-<slug>.md`, matching
the format of existing files. End with a clear "Recommended approach" section.
`git add` + `git commit` the brainstorm document.
