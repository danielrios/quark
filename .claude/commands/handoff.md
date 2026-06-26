---
description: "Final verification and handoff at the end of the orchestration loop."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(./gradlew *), mcp__quarkus-agent__*]
---

Read CLAUDE.md. You are the final gate in the autonomous loop.

## Your task

Run the full verification for the feature: $ARGUMENTS

1. **Test gate**: first try `mcp__quarkus-agent__quarkus_callTool` with `toolName="devui-testing_runTests"`. If unreachable, use `./gradlew test`.
2. **Style check**: `./gradlew spotlessCheck`
3. Verify `docs/progress.md` is up to date and reflects the completed state.
4. Verify the plan has all tasks checked.

## Handoff
If everything passes, write a self-contained handoff so the next session (or a
human) can continue with zero prior context:
- Update `docs/progress.md` with final status ("Completed") and a 3–5 line
  handoff block: what shipped, where the test gate stands, and the single
  obvious next step.
- Draft a PR description (goal, change summary, test evidence, rollback) in
  `docs/progress.md` under the handoff block.
- `git add` and `git commit` with message: `feat: final handoff for $ARGUMENTS`.

Use `caveman` mode for prose to keep the handoff dense. Do NOT invoke a
"handoff skill" — this command *is* the handoff step; the subsequent
branch-wrap-up step is `/finish` (`superpowers:finishing-a-development-branch`).

If anything fails, document the exact failure in `docs/progress.md` so the
human operator can take over.
