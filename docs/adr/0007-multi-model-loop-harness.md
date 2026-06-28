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

### Skill map & loop contracts

Each phase wraps a skill (superpowers / Matt Pocock engineering skills /
caveman) plus the project-specific contract it must honor:

| Phase      | Wraps                                                                                  |
|------------|----------------------------------------------------------------------------------------|
| Brainstorm | `superpowers:brainstorming` + `domain-modeling` + `grilling`                            |
| Spec       | bespoke + `domain-modeling` + `grilling`                                                |
| Plan       | `superpowers:writing-plans` + `codebase-design`                                         |
| Implement  | `superpowers:executing-plans` + `:test-driven-development` + `caveman` + `cavecrew`     |
| Review     | `superpowers:requesting-/receiving-code-review` + `codebase-design` + `caveman`         |
| Simplify   | bespoke + `caveman`                                                                     |
| Advisor    | `superpowers:systematic-debugging` + `diagnosing-bugs`                                  |
| Handoff    | bespoke (self-contained; writes handoff block + PR draft to `docs/progress.md`)         |
| Finish     | `superpowers:finishing-a-development-branch`                                            |

Three contracts make the loop mechanically reliable:

1. **Artifact resolution.** The skills choose their own `<slug>`/`<date>`, so
   `orchestrate.sh` does not trust its bash-computed path. After each producing
   phase it re-resolves the newest file in `docs/superpowers/<kind>/`
   (`resolve_output`), warns on mismatch, and aborts if a *required* input
   (spec→plan, plan→implement) is missing — instead of silently running a phase
   on a non-existent file.
2. **Completion marker.** The implement loop's only early exit is
   `.claude/state/IMPL_COMPLETE`. `/implement` creates it *only* when every plan
   checkbox is done and the test gate is green; otherwise the loop iterates to
   its cap. Plans therefore MUST use `- [ ]` checkboxes for every task.
3. **Context economy.** `caveman` mode is mandatory on the high-volume phases
   (implement, review, simplify); `cavecrew` subagents absorb "locate code" and
   "isolated 1–2 file edit" sub-steps so the long implement loop's main context
   survives.

Triage/issue and domain-doc conventions the skills read live in `docs/agents/`
(GitHub issues, default triage labels, single-context `CONTEXT.md`).

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

**Secrets.** `.mcp.json` is committed, so it must never carry a literal key. It
references `${CONTEXT7_API_KEY}` (Claude Code expands `${VAR}`/`${VAR:-default}`
in `.mcp.json`); the value is supplied via `.env` (gitignored) or the host
environment and passed through by `docker-compose.claude.yml`. `.env.example`
documents the required variables. An earlier revision of this branch committed a
literal key — rotate any key that has ever been committed.

### Launching the loop — `scripts/loop.sh`

`docker compose` v2 is not guaranteed on the host (the dev machine had genuine
Docker but no compose plugin). **`scripts/loop.sh` is the canonical launcher** —
a plain `docker run` wrapper, no compose dependency:

* `scripts/loop.sh run --feature "…"` — runs `orchestrate.sh` in the container
* `scripts/loop.sh exec <cmd…>` — one-shot (used for the readiness smoke)
* `scripts/loop.sh shell` — interactive debug

`docker-compose.claude.yml` is kept in sync as a best-effort alternative for
hosts that do have compose.

### Propagating skills into the container (non-obvious)

The loop's wrappers depend on `caveman`, `cavecrew`, and the Matt Pocock
engineering skills (`domain-modeling`, `diagnosing-bugs`, `grilling`,
`codebase-design`, …). Getting them to load inside the container took two fixes
beyond mounting `~/.claude`:

1. **Symlink targets.** `~/.claude/skills/*` are *relative symlinks* into
   `~/.agents/skills/*`. Mounting only `~/.claude` leaves them dangling, so
   `~/.agents` is mounted too.
2. **Registration.** Personal skills are registered via `~/.claude.json`, not by
   scanning the dir — with only the Dockerfile's minimal stub the skills stay
   invisible even when the files are present and readable. `loop.sh` mounts an
   *ephemeral copy* of the host `~/.claude.json` (with `/workspace` trust merged
   in); the host file is never mutated.

Verified via `scripts/loop.sh exec claude …`: all nine target skills report
**Found**, and both `quarkus-agent` and `context7` MCPs connect in-container.
Trade-off: mounting the full `~/.claude.json` also surfaces the user's other
claude.ai connectors in the container — harmless for a local run, trim later if
it matters.

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
  `scripts/orchestrate.sh`, slash commands in `.claude/commands/`,
  `.env.example`, `docs/agents/` (issue-tracker / triage-labels / domain)
* The existing harness (hooks, settings, CLAUDE.md) remains unchanged
  and continues to protect even inside the container.
* Cost: hybrid model usage reduces Opus spend by ~40-60% vs all-Opus
  while maintaining review quality.

## Revisit triggers

* Claude Code adds native multi-model orchestration (making the bash
  script redundant)
* A single model achieves Opus-quality review at Sonnet speed/cost
* The project outgrows single-container execution
