<!--
This template enforces the single highest-leverage gap from the harness
review: today the §3 baseline test gate is honor-system. Pasting the MCP
output here converts the agent's claim into a reviewable artefact.
-->

## Linked plan / spec

<!-- e.g. docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md, or a plan under docs/superpowers/plans/ once authored -->



## What changed

<!-- 2-4 bullets. Why, not what — the diff is the what. -->



## Baseline test gate evidence (CLAUDE.md §3)

<!--
Paste the output of: mcp__quarkus-agent__quarkus_callTool with
toolName="devui-testing_runTests" — both BEFORE and AFTER the change.
If the MCP was unreachable, say so explicitly and link the CI run as
the fallback verification.
-->

**Before:**

```
```

**After:**

```
```

## Checklist

- [ ] `./gradlew spotlessCheck` is clean (CI also enforces).
- [ ] `docs/progress.md` updated if the change is mid-plan.
- [ ] No abstractions added that aren't required by today's task (ADR 0003, CLAUDE.md §6 YAGNI).
- [ ] If a new architectural decision was made, ADR drafted under `docs/adr/`.
