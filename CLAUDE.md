# Agent Operating Rules & Constraints

This file is the contract between Claude Code sessions and the `quark`
repository. It tells the agent what to read first, what to verify before
and after changes, and what the harness in `.claude/` will block or warn
about.

## Project pointer (read these before editing)

- [README.md](./README.md) — what `quark` is today, MVP scope, run instructions.
- [MANIFESTO.md](./MANIFESTO.md) — engineering philosophy (explicit > magical, streaming-first, additive evolution).
- [ARCHITECTURE.md](./ARCHITECTURE.md) — current MVP pipeline **and** the destination event-driven runtime, in one document with a clear split.
- [docs/adr/](./docs/adr/) — load-bearing decisions with context, alternatives, revisit triggers.
- [docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md](./docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md) — the MVP design spec.
- [docs/superpowers/plans/](./docs/superpowers/plans/) — ordered implementation plans. Do not skip ahead of the next unfinished plan.

The MVP is intentionally smaller than the destination architecture in
ADR 0001/0002. ADR 0003 explains the sequencing. Read it before
introducing any runtime abstraction.

## 1. System Environment & Stack

- **Stack:** Java 25, Quarkus 3.35.4, Gradle Kotlin DSL (`./gradlew`), `quarkus-langchain4j-core` via Quarkiverse.
- **Package root:** `com.quark`. Package layout stays flat (`chat`, `telegram`, `rest`, `config`, `shared`) until ADR 0003's refactor phase.
- **Style:** idiomatic Quarkus/CDI; virtual threads are first-class.
- **Dev mode:** never run `./gradlew quarkusDev` (or any `quarkus:dev`) in the foreground — drive dev mode through the `quarkus-agent` MCP.

## 2. Execution Constraints (Deterministic Guardrails)

- **WIP = 1.** You are strictly forbidden from working on more than one feature or file concern at a time. Finish the current change before starting another.
- **State rule.** If a change or intent is not committed to git or written to [`docs/progress.md`](./docs/progress.md), it does not exist. The session ends; the file does not.
- **Non-interactive.** Do not run bash commands that block the terminal or expect human input. Use the `quarkus-agent` MCP (`quarkus_start`, `quarkus_status`, `quarkus_logs`) for dev mode.

## 3. Tool & Verification Pipeline

- **Before** making any code change, run the baseline verification via the `quarkus-agent` MCP: `quarkus_callTool` with `toolName: "devui-testing_runTests"`. Record the pre-state.
- **After** every code modification, run the same check.
- **Victory condition.** A task is only done when the test runner reports zero failures and zero errors. You may not declare a task done based on reading the diff or visual inspection.
- If the MVP code does not exist yet and there is nothing for the test runner to exercise beyond `GreetingResourceTest`, that is still the gate — record the result and proceed.

The `/baseline-test` slash command wraps this.

## 4. Failure Escalation (Stop Hook)

- If the test runner fails **3 times** with the same error on the same change, **stop**. Do not attempt a 4th time.
- Write the full stack trace to `docs/progress.md` under "Active Trajectory Logs", state what you tried, and ask the human for clarification.
- Two attempts is too tight for Java 25 + Quarkus cold-start noise; four is wasteful.

## 5. Harness Affordances (`.claude/`)

Pre-approved (no prompt):

- `Read`, `Edit`, `Write`.
- Read-only git: `status`, `diff`, `log`, `show`, `branch`, `fetch`.
- Non-dev-mode Gradle: `test`, `check`, `compileJava`, `spotless*`, `build -x test`, `tasks`.
- The `mcp__quarkus-agent__*` namespace.
- The `mcp__context7__*` namespace and the `quarkus-agent` doc tools — framework/library documentation lookup. Use these to verify documented behavior before decompiling jars (see §8).

Still prompts:

- `git add`, `git commit`, `git push`.
- `./gradlew clean` (drops test classes; breaks the dev-mode test runner).
- Any destructive filesystem op.

Hard-blocked at the harness layer:

- `./gradlew quarkusDev` and any foreground dev-mode variant. Use the MCP per §1.

Slash commands (`.claude/commands/`):

- `/baseline-test` — runs the `quarkus-agent` MCP test gate (§3) and reports.
- `/progress <one-liner>` — appends a timestamped entry to `docs/progress.md` (§2 state rule).
- **Loop phases** (driven by `scripts/orchestrate.sh`, see §9): `/brainstorm`,
  `/spec`, `/plan`, `/implement`, `/review`, `/simplify`, `/advisor`,
  `/handoff`, `/finish`. Each is a thin wrapper over a superpowers / Matt Pocock
  skill plus this project's contract (output path, test gate, completion marker).

`SessionStart` warns if Java 25, `gradlew`, or the `quarkus-agent` MCP are missing.

Full rationale: [ADR 0004](./docs/adr/0004-claude-code-harness.md).

## 5a. Autonomous loop & skill stack

The multi-model loop ([ADR 0007](./docs/adr/0007-multi-model-loop-harness.md),
`scripts/orchestrate.sh`) routes each lifecycle phase to a model and a skill:

| Phase | Model | Wraps |
|-------|-------|-------|
| brainstorm | Opus | `superpowers:brainstorming` + `domain-modeling` + `grilling` |
| spec | Opus | bespoke + `domain-modeling` + `grilling` |
| plan | Opus | `superpowers:writing-plans` + `codebase-design` |
| implement | Sonnet | `superpowers:executing-plans` + `:test-driven-development` + `caveman` + `cavecrew` |
| review | Opus | `superpowers:requesting-/receiving-code-review` + `codebase-design` + `caveman` |
| simplify | Sonnet | bespoke + `caveman` |
| advisor | Opus | `superpowers:systematic-debugging` + `diagnosing-bugs` (3-strike escalation, §4) |
| handoff / finish | Sonnet | bespoke / `superpowers:finishing-a-development-branch` |

Contracts the loop depends on (do not break silently):

- **Artifact paths.** Each producing phase writes `docs/superpowers/<kind>/<date>-<slug>.md`;
  the next phase re-resolves the newest file in that dir, so a slug/date mismatch warns
  instead of feeding a missing path forward.
- **Completion marker.** `/implement` (or `/advisor`) creates
  `.claude/state/IMPL_COMPLETE` only when every plan checkbox is done and the gate is
  green. That marker is the *only* early exit from the implement loop.
- **`caveman` mode** is mandatory for the high-volume phases (implement, review, simplify)
  to conserve context; `cavecrew` subagents handle "locate code" / "isolated edit"
  sub-steps in the long implement loop.
- **Issue-tracker / triage / domain docs** live in `docs/agents/` (GitHub issues,
  default triage labels, single-context `CONTEXT.md`); skills read them there.
- **Secrets.** `.mcp.json` reads `${CONTEXT7_API_KEY}` from the environment — never
  hardcode it. Set it in `.env` (gitignored) or your shell; docker-compose passes it
  through. See `.env.example`.

## 6. Engineering Principles

- **Least power (agent layer).** When *you, the agent*, are solving a deterministic problem during development, choose the least powerful tool that works: a loop before regex, regex before a parser, a parser before an LLM call. This constrains *agent reasoning*, not the product — `langchain4j` and `@RegisterAiService` remain first-class inside shipped code.
- **YAGNI / KISS.** Implement the simplest code that solves today's task. No abstract factories, SPI seams, or layering "in case." Abstractions are introduced on the schedule in [ADR 0003](./docs/adr/0003-walking-skeleton-first-plan-sequencing.md); do not pre-empt it.
- **No guessing.** You may not declare a change correct based on code reading alone. The MCP test gate in §3 is the only authoritative answer — not `./gradlew test`, not `npm test`, not "looks right." Caveat (§8): a green gate proves only what the tests *exercise*; lifecycle and cross-request persistence behavior is not certified until a test drives the real scope boundary, or a live smoke confirms it.
- **Architecture decisions go somewhere.** Either an ADR in `docs/adr/`, a `docs/progress.md` note, or the PR body. Never silenced; never narrated only in chat.
- **Output discipline.** No conversational filler, no restating the request, no pleasantries. Terse and concrete.

## 7. Don't drift from the destination

The destination architecture (`AgentRuntime`, `AgentEvent`,
`ModelGateway`, layered packages, ArchUnit) is real and documented in
ADR 0001, ADR 0002, and the "Destination" section of `ARCHITECTURE.md`.
The MVP intentionally does not implement it yet. When MVP code starts
landing in Plans 1–3, shape every file so the refactor phase in ADR 0003
(Plan 4) can extract those seams cleanly from working code. If you find
yourself wanting to design around the absent abstraction before code
exists, that is a signal to re-read ADR 0003 first.

## 8. Lifecycle changes & deletion discipline (learned from PR #14)

CDI scopes and beans, producers, `ChatMemory` / `ChatMemoryStore`, and any
`@PreDestroy` / `@Observes` handler are **lifecycle-bearing**. Conversation
memory was lost across Telegram updates ([ADR 0006](./docs/adr/0006-application-scoped-ai-service-for-memory.md))
because such a component was deleted on bytecode reasoning that a green unit
test appeared to confirm — twice. The rules below are the cost of that and
are binding for any lifecycle-bearing change.

- **Docs first, bytecode last.** When unsure how a framework manages a
  scope, bean, or memory lifecycle, consult the docs via the `context7` or
  `quarkus-agent` MCP *before* decompiling jars. Bytecode confirms a
  documented contract; it does not substitute for reading it.
- **No "redundant" verdict on a lifecycle component without a doc check.**
  Before calling such a class redundant, obsolete, or safe to delete, verify
  its documented lifecycle behavior via the doc MCPs and cite the source in
  the PR body or an ADR. "It's a singleton" / "the diff looks dead" is a fact,
  not a verdict.
- **A green test proves only what it exercises.** A test that does not drive
  the real scope boundary — CDI request-context activate/terminate, bean
  `@PreDestroy` — is not evidence about lifecycle or cross-request
  persistence, even when it passes. (`--rerun-tasks` and a clean suite change
  nothing here; the gap is *test altitude*, not the runner.) For persistence
  behavior, write an integration test that reproduces the real path: an
  independent request context per call, mirroring one poll / webhook cycle
  (see `TelegramConversationMemoryTest`).
- **Docs vs. empirics conflict → probe, don't delete.** If a local result
  disagrees with the official docs, resolve it with an isolated diagnostic
  probe and/or the integration test above *before* proposing any deletion of
  a lifecycle component — never by guessing which side is right.
- **A test pass is not a live verification.** Record behavior as "verified"
  only from an integration test that drives the real boundary, or from a live
  smoke; state which in `docs/progress.md`. Never write "live-confirmed" off a
  green suite alone.

**Enforcement.** The deletion rule is backed by `.claude/hooks/pre-delete-guard.sh`
(PreToolUse on Bash): it emits this §8 reminder when an `rm` / `git rm` targets a
lifecycle-bearing `src/main/**.java` file (one carrying a CDI scope, `@Produces`,
`@PreDestroy`, `@Observes`, or `ChatMemory*` / `*Store`). Advisory only — it warns, it
does not block. The deterministic backstop is the CI e2e test
(`TelegramConversationMemoryTest` in `ci.yml`): break the scope and it goes red.

## Agent skills

### Issue tracker

Issues live in GitHub Issues; `gh` CLI is used for all operations. External PRs are not a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

Default canonical labels in use (no overrides). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo — one `CONTEXT.md` (created lazily by `/domain-modeling`) + `docs/adr/` at the root. See `docs/agents/domain.md`.
