---
description: "Final verification and handoff at the end of the orchestration loop."
allowed-tools: [Read, Bash(git *), Bash(./gradlew *), mcp__quarkus-agent__*]
---

Read CLAUDE.md. You are the final gate in the autonomous loop.

## Your task

Use the `superpowers:verification-before-completion` skill to run the full verification for the feature: $ARGUMENTS

1. **Test gate**: first try `mcp__quarkus-agent__quarkus_callTool` with `toolName="devui-testing_runTests"`. If unreachable, use `./gradlew test`.
2. **Style check**: `./gradlew spotlessCheck`
3. Verify `docs/progress.md` is up to date and reflects the completed state.
4. Verify the plan has all tasks checked.

## Handoff
If everything passes:
- Update `docs/progress.md` with final status ("Completed").
- Prepare a PR description summarizing the feature in `docs/progress.md`.
- `git add` and `git commit` with message: `feat: final handoff for $ARGUMENTS`

If anything fails, document the exact failure in `docs/progress.md` so the human operator can take over.
