---
description: "Act as an Opus-tier Advisor to diagnose and fix stubborn bugs that Sonnet couldn't resolve after multiple attempts."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(./gradlew *), Bash(cat *), Bash(find *), Bash(ls *), Bash(grep *), Bash(rg *), mcp__quarkus-agent__*, mcp__context7__*]
---

Read these project files first:
- CLAUDE.md (binding contract — especially §4 escalation, §8 lifecycle)
- The plan referenced in $ARGUMENTS
- docs/progress.md (which contains the error traces from failed attempts)
- Recent loop logs in `.claude/state/loop-logs/` (if needed for context)

## Your task

The Sonnet implementer has failed multiple times on the current task. You are called in as an expert to diagnose and fix the issue.

1. **Read the error traces** in `docs/progress.md`
2. **Diagnose the root cause**
3. **Fix the issue**
4. **Run the test gate**:
   - Primary: `mcp__quarkus-agent__quarkus_callTool` with `toolName="devui-testing_runTests"`
   - For docs: `mcp__quarkus-agent__quarkus_searchDocs`, `mcp__context7__*`
   - Fallback: `./gradlew test`
5. If successful, mark the task done in the plan and update `docs/progress.md`.
6. If the fix works, and all tasks are done, create `.claude/state/IMPL_COMPLETE`
7. `git add` and `git commit` your fix with a descriptive message.

## Rules
- Follow CLAUDE.md §8 lifecycle discipline if touching CDI components.
- Consult docs via MCP before making assumptions.
