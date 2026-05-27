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

Still prompts:

- `git add`, `git commit`, `git push`.
- `./gradlew clean` (drops test classes; breaks the dev-mode test runner).
- Any destructive filesystem op.

Hard-blocked at the harness layer:

- `./gradlew quarkusDev` and any foreground dev-mode variant. Use the MCP per §1.

Slash commands:

- `/baseline-test` — runs the `quarkus-agent` MCP test gate (§3) and reports.
- `/progress <one-liner>` — appends a timestamped entry to `docs/progress.md` (§2 state rule).

`SessionStart` warns if Java 25, `gradlew`, or the `quarkus-agent` MCP are missing.

Full rationale: [ADR 0004](./docs/adr/0004-claude-code-harness.md).

## 6. Engineering Principles

- **Least power (agent layer).** When *you, the agent*, are solving a deterministic problem during development, choose the least powerful tool that works: a loop before regex, regex before a parser, a parser before an LLM call. This constrains *agent reasoning*, not the product — `langchain4j` and `@RegisterAiService` remain first-class inside shipped code.
- **YAGNI / KISS.** Implement the simplest code that solves today's task. No abstract factories, SPI seams, or layering "in case." Abstractions are introduced on the schedule in [ADR 0003](./docs/adr/0003-walking-skeleton-first-plan-sequencing.md); do not pre-empt it.
- **No guessing.** You may not declare a change correct based on code reading alone. The MCP test gate in §3 is the only authoritative answer — not `./gradlew test`, not `npm test`, not "looks right."
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
