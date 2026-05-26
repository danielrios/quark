# Agent Operating Rules & Constraints

## Project Context (optional, for more context if needed)

- [ARCHITECTURE.md](./ARCHITECTURE.md) — pipeline shape, layered packages, what's actually built today vs. what's designed.
- [docs/adr/](./docs/adr/) — load-bearing decisions with context, alternatives, and revisit triggers.
- [docs/superpowers/specs/](./docs/superpowers/specs/) — design specs (slice 1 spec is the destination, not the current state).
- [docs/superpowers/plans/](./docs/superpowers/plans/) — ordered implementation plans; do not skip ahead.

## 1. System Environment & Stack
- Core Stack: Java 25, Quarkus 3.35.4, Gradle Kotlin DSL (`./gradlew`), langchain4j via Quarkiverse. Package root `com.quark`.
- Code Style: idiomatic Quarkus/CDI; virtual threads first-class; no raw `mvn`/`gradle` invocations while dev mode runs (use the quarkus-agent MCP).

## 2. Execution Constraints (Deterministic Guardrails)
- WIP Limit: You are strictly forbidden from working on more than one feature or file at a time. WIP = 1.
- State Rule: If a file change or intent is not committed to Git or written to `docs/progress.md`, it does not exist.
- Non-Interactive: Do not run bash commands that block the terminal or expect human input (e.g., `./gradlew quarkusDev` in the foreground). Drive dev mode through the quarkus-agent MCP (`quarkus_start`, `quarkus_status`, `quarkus_logs`).

## 3. Tool & Verification Pipeline
- BEFORE making any changes, you MUST run the baseline verification check via the quarkus-agent MCP: `quarkus_callTool` with `toolName: "devui-testing_runTests"`.
- AFTER every code modification, you MUST run the same check: `quarkus_callTool` with `toolName: "devui-testing_runTests"`.
- Victory Condition: You are NOT allowed to declare a task "done" based on your own evaluation. A task is only done when the test runner reports all tests pass (zero failures, zero errors).

## 4. Failure Escalation (Stop Hooks)
- If a test fails 3 times consecutively with the same error, STOP execution immediately.
- Do not attempt a 4th time. Write the exact stack trace to `docs/progress.md` and ask the human for clarification.

## 5. Harness Affordances (`.claude/`)
- Permissions are pre-approved for: `Read`, `Edit`, `Write`, read-only `git` (`status`/`diff`/`log`/`show`/`branch`/`fetch`), non-dev-mode `./gradlew` (`test`, `check`, `compileJava`, `spotless*`, `build -x test`, `tasks`), and the `mcp__quarkus-agent__*` namespace. Anything that writes (commits, pushes, gradle clean) still prompts.
- **Hard-blocked at the harness layer:** `./gradlew quarkusDev` and friends. Use the `quarkus-agent` MCP (`quarkus_start`, `quarkus_status`, `quarkus_logs`) per §2.
- Slash commands:
  - `/baseline-test` — invokes the `quarkus-agent` MCP test gate (CLAUDE.md §3) and reports results.
  - `/progress <one-liner>` — appends a timestamped entry to `docs/progress.md` (CLAUDE.md §2 state rule).
- SessionStart prints the current docs/progress.md task and warns if Java 25 / `gradlew` / `quarkus-agent` MCP are missing.
- Full design rationale: [`docs/adr/0004-claude-code-harness.md`](./docs/adr/0004-claude-code-harness.md).

## 6. Engineering Principles (Least Power, YAGNI, Output Discipline)
- **Least Power (agent layer).** When *you, the agent*, are solving a deterministic problem during development, choose the least powerful tool that works: `if`/loop/script before regex, regex before a parser, parser before an LLM call. This constrains *agent reasoning*, not the product — langchain4j and `@RegisterAiService` are first-class in shipped code.
- **YAGNI / KISS.** Implement the simplest code that solves today's task. No abstract factories, SPI seams, or layering "in case." Abstractions are introduced on the schedule in [ADR 0003](./docs/adr/0003-walking-skeleton-first-plan-sequencing.md); do not pre-empt it.
- **No Guessing.** You may not declare a change correct based on code reading alone. Verification is §3's MCP test gate (`quarkus_callTool` → `devui-testing_runTests`), not any other runner. Do not substitute `./gradlew test`, `pytest`, `npm test`, or terminal echoes of "looks good" for that gate.
- **Escalation Threshold (clarification, not override).** §4's 3-failure rule stands. Two is too tight for Java 25 + Quarkus cold-start noise; four wastes a session. Stop at 3, write the stack trace to `docs/progress.md`, ask.
- **Output Discipline.** No conversational filler, no restating the request, no pleasantries. Architectural decisions still go *somewhere* — into an ADR under `docs/adr/`, a `docs/progress.md` note, or the PR body — never silenced and never inline-narrated in chat.
