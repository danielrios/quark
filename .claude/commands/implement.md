---
description: "Execute the next task from the current plan. Sonnet-tier: fast implementation with full codebase context."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(./gradlew *), Bash(cat *), Bash(find *), Bash(ls *), Bash(grep *), Bash(rg *), Bash(java *), Bash(curl *), mcp__quarkus-agent__*, mcp__context7__*]
---

Read these project files first:
- CLAUDE.md (binding contract — ALL rules are active)
- The plan file referenced in $ARGUMENTS (or the latest in `docs/superpowers/plans/`)
- docs/progress.md (current state)

## Your task

Execute the NEXT unchecked task from the plan. Find the first `- [ ]` entry.

## Tool strategy (follow this order)

### Tests and logs
1. **Primary:** `mcp__quarkus-agent__quarkus_callTool` with `toolName="devui-testing_runTests"`
2. **Fallback:** `./gradlew test` (if MCP unreachable)
3. **Logs:** `mcp__quarkus-agent__quarkus_logs`

### Documentation lookup
1. **Official Quarkus docs:** `mcp__quarkus-agent__quarkus_searchDocs`
2. **Quarkiverse / langchain4j / other libs:** `mcp__context7__resolve-library-id` → `mcp__context7__query-docs`
3. The quarkus-agent covers official Quarkus only. For extensions (quarkus-langchain4j etc.), always use context7.

### Style
- `./gradlew spotlessCheck` / `./gradlew spotlessApply`

## After implementation
1. Run the test gate (see tool strategy)
2. Run `./gradlew spotlessCheck`
3. Mark the task as done in the plan: change `- [ ]` to `- [x]`
4. Update docs/progress.md with what you did
5. `git add` and `git commit` with a descriptive message

## Rules
- CLAUDE.md §2: WIP=1 — one task only, finish before starting another
- CLAUDE.md §3: test gate before AND after changes
- CLAUDE.md §4: if tests fail 3 times with same error, STOP, write trace to progress.md, ask for help
- CLAUDE.md §8: if touching CDI/lifecycle components, check docs via MCP FIRST
