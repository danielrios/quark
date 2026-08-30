---
description: "Implement the tasks in a plan, test-first. Sonnet-tier: fast coding with full codebase context."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(./gradlew *), Bash(cat *), Bash(find *), Bash(ls *), Bash(grep *), Bash(rg *), mcp__quarkus-agent__*, mcp__context7__*]
---

Implement the tasks defined in the plan file: $ARGUMENTS

Read first: CLAUDE.md (binding contract), the plan in $ARGUMENTS, docs/progress.md.

## Skills to use
- `superpowers:executing-plans` — work the plan task-by-task with review checkpoints.
- `superpowers:test-driven-development` — red → green → refactor for every task.
- `caveman` (caveman mode) — compress all prose responses; keep full technical accuracy.
- `cavecrew` — for any sub-step that means "find where X lives" or "make this isolated 1–2 file edit," delegate to a `cavecrew-investigator` / `cavecrew-builder` subagent so the long implement loop conserves main-thread context (this phase runs up to 15 iterations).

## Test gate (CLAUDE.md §3 — authoritative)
1. **Primary:** `mcp__quarkus-agent__quarkus_callTool` with `toolName="devui-testing_runTests"`.
2. **Docs:** `mcp__quarkus-agent__quarkus_searchDocs`; other libs `mcp__context7__*`.
3. **Fallback:** `./gradlew test` only if the MCP is unreachable.

## Per-task loop
1. Implement the next unchecked task in the plan.
2. Run the test gate. If it fails, do NOT proceed — fix or escalate per CLAUDE.md §4 (stop after 3 same-error attempts; write the trace to docs/progress.md).
3. `./gradlew spotlessCheck`.
4. `git add` + `git commit` the task as one atomic commit.
5. Check the task's box in the plan and append a one-line entry to docs/progress.md.

## Termination contract (REQUIRED — the orchestrator depends on this)
When **every** task in the plan is checked AND the test gate reports zero failures/zero errors:
1. Make a final commit if anything is uncommitted.
2. Create the completion marker so `scripts/orchestrate.sh` exits the implement loop instead of grinding to its iteration cap:

   ```bash
   mkdir -p .claude/state && touch .claude/state/IMPL_COMPLETE
   ```

If tasks remain or the gate is red, do NOT create the marker — exit normally so the loop runs another iteration.
