---
description: "Address review findings and refactor for clarity. Sonnet-tier: codebase-wide simplification."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(./gradlew *), Bash(cat *), Bash(find *), Bash(ls *), Bash(grep *), Bash(rg *), mcp__quarkus-agent__*, mcp__context7__*]
---

Read these project files first:
- CLAUDE.md (binding contract — especially §6 YAGNI/KISS, §8 lifecycle discipline)
- The review referenced in $ARGUMENTS (or the latest in `docs/superpowers/reviews/`)
- docs/progress.md

## Your task

Address each finding from the review document:

1. **🔴 Blockers** — Fix all of these. They are mandatory.
2. **🟡 Improvements** — Address those that align with CLAUDE.md §6 (YAGNI/KISS)
3. **🟢 Nits** — Apply if they genuinely improve readability

## Tool strategy (same as /implement)

### Tests
1. **Primary:** `mcp__quarkus-agent__quarkus_callTool` with `toolName="devui-testing_runTests"`
2. **Fallback:** `./gradlew test`

### Docs
1. **Official Quarkus:** `mcp__quarkus-agent__quarkus_searchDocs`
2. **Other libs:** `mcp__context7__resolve-library-id` → `mcp__context7__query-docs`

## After EACH fix
1. Run the test gate — do NOT proceed to next fix if tests fail
2. Run `./gradlew spotlessCheck`
3. `git add` and `git commit` each logical fix separately (atomic commits)
4. Update docs/progress.md

## Rules
- CLAUDE.md §3: test gate after every change
- CLAUDE.md §4: 3-strike escalation on repeated failures
- CLAUDE.md §8: if the review flags lifecycle concerns, verify via doc MCP before changing
- Do not over-engineer fixes — simplest correct solution wins
