# 0007 — Multi-model autonomous loop harness (Docker)

**Status:** Proposed, 2026-06-26
**Deciders:** Daniel + loop-engineering session
**Context refs:** [`CLAUDE.md`](../../CLAUDE.md), [ADR 0004](0004-claude-code-harness.md), [`scripts/orchestrate.sh`](../../scripts/orchestrate.sh)

---

## Context

The existing Claude Code harness (ADR 0004) provides guardrails for
interactive, single-session development. As the project scales, we need
autonomous, multi-feature execution that:

* survives context window degradation across long tasks,
* uses the right model for each phase of the development lifecycle,
* runs in an isolated Docker environment with full internet access,
* integrates with the existing test gate, hooks, and progress ledger.

## Decision

Adopt a **multi-model orchestration architecture** running inside Docker:

| Phase       | Model      | Rationale                                        |
|-------------|------------|--------------------------------------------------|
| Orchestrate | Opus       | Deep reasoning for routing and quality gates      |
| Brainstorm  | Opus       | Creative exploration of trade-offs                |
| Spec        | Opus       | Rigorous requirement definition                   |
| Plan        | Opus       | Dependency-aware task decomposition               |
| Implement   | Sonnet     | Fast, cost-efficient coding with full codebase    |
| Review      | Opus       | Adversarial cross-model review                    |
| Simplify    | Sonnet     | Codebase-wide refactoring with broad context      |

### Tool strategy

* **Primary test/log/doc tool:** `quarkus-agent` MCP (tests via
  `devui-testing_runTests`, logs, official Quarkus docs)
* **Supplementary docs:** `context7` MCP (Quarkiverse, langchain4j,
  and other libraries not covered by the quarkus-agent)
* **Fallback:** `./gradlew test`, `./gradlew spotlessCheck` when MCP
  is unreachable

### Escalation protocol

If the Sonnet implementer fails the same task 3 times, the orchestrator
calls an Opus "advisor" subagent to diagnose the root cause. If the
advisor also fails after 2 attempts, the loop exits with a full error
report in `docs/progress.md` (5 total attempts ceiling).

### Docker isolation

* Image: Eclipse Temurin 25 + Node.js 22 + JBang + Claude Code
* Non-root user (`claude`, UID 1000)
* Named volumes for auth (`~/.claude`) and Gradle cache (`~/.gradle`)
* `quarkus-agent` MCP runs inside the container via JBang stdio
* `context7` MCP connects externally via streamable-http
* Internet access: unrestricted (API, Maven Central, npm, GitHub)

## Alternatives considered

1. **Single-model bash loop** — simpler, but no cross-model review and
   no model-appropriate routing. Context degrades in long sessions.
2. **Sonnet 1M as orchestrator** — attractive for its large context
   window, but orchestration requires reasoning depth over breadth.
   Opus excels at evaluating whether subagent output meets spec.
3. **No Docker isolation** — `--dangerously-skip-permissions` on the
   host is unacceptable. Docker provides blast-radius containment.

## Consequences

* New files: `Dockerfile.claude-loop`, `docker-compose.claude.yml`,
  `scripts/orchestrate.sh`, slash commands in `.claude/commands/`
* The existing harness (hooks, settings, CLAUDE.md) remains unchanged
  and continues to protect even inside the container.
* Cost: hybrid model usage reduces Opus spend by ~40-60% vs all-Opus
  while maintaining review quality.

## Revisit triggers

* Claude Code adds native multi-model orchestration (making the bash
  script redundant)
* A single model achieves Opus-quality review at Sonnet speed/cost
* The project outgrows single-container execution
