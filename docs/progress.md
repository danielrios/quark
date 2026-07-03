# Task Progress Ledger

Mutable agent state. The MVP design lives in
[`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](./superpowers/specs/2026-05-25-agent-runtime-mvp.md);
do not duplicate it here. Once a Plan 1 file exists under
`docs/superpowers/plans/`, point at it instead. This file holds only:
current task pointer, session counter, last ~3 trajectory entries, and
live stack traces.

## Current Task
- Plan 3 — Telegram streaming via throttled edits — **DONE** (merged via PR #22, release 0.4.0).
- **In progress: Plan 4 — runtime extraction** (`AgentRuntime`, `AgentEvent`, `ModelGateway`, `ChatMemoryStore`) — plan file: [superpowers/plans/2026-07-03-plan-4-runtime-extraction.md](superpowers/plans/2026-07-03-plan-4-runtime-extraction.md), branch `plan-4-runtime-extraction`. Baseline gate 2026-07-03: `./gradlew test` → 32 tests, 0 failures, 0 errors.

## System State
- Current Session Attempts: 0 / 3
- Git Branch: see `git branch --show-current`
- **Test gate fallback active:** `quarkus_callTool devui-testing_runTests` cannot detect HTTP port (persistent MCP bug across all stop/start cycles). Using `./gradlew test` as gate — harness pre-approved, Java 25 confirmed available.
- **Last gate (Plan 1 close-out):** `./gradlew test` → BUILD SUCCESSFUL, 10 tests, zero failures. Gotcha: keep dev mode off port 8081 — it collides with Quarkus's default test port and fails `@QuarkusTest` binding.

## Active Trajectory Logs / Error Traces
<!-- Append the most recent entry at the top. Trim older entries on each session — they live in the git log and PR descriptions, not here. -->

### 2026-06-26 01:46Z — Plan 3 implementation complete — Telegram streaming via throttled edits
- branch: plan-3-telegram-streaming
- status: All tasks done (Tasks 1-5). `TelegramStreamHandler` (CountDownLatch streaming loop, 4 TDD tests), DTOs, `streamChat()` on `Assistant`, `handle()` wired for streaming CHAT path. 30 tests, 0 failures. Gate: MCP `devui-testing_runTests`. Next: `/simplify`, open PR, `/requesting-code-review` Opus, `/finishing-a-development-branch` with manual Telegram smoke.

### 2026-05-30 — Governance: CLAUDE.md §8 (lifecycle/deletion discipline) + context7 MCP
- Added **§8** to CLAUDE.md capturing the PR #14 lesson as binding rules for lifecycle-bearing changes: docs-first/bytecode-last; no "redundant" verdict on a lifecycle component without a doc-MCP check + citation; a green test proves only what it exercises (test *altitude*, not `--rerun-tasks`, was the false-positive cause); docs-vs-empirics conflict → probe, don't delete; a test pass is not a live verification.
- Registered `mcp__context7__*` + quarkus-agent doc tools as pre-approved doc-lookup affordances (§5), and added a §8 caveat to the "No guessing" principle (§6) — a green gate certifies only what tests exercise.
- Note: adapted Daniel's proposed Rule 2 — its stated mechanism ("`--rerun-tasks` / continuous runner scope causes persistence false positives") is technically wrong; the real cause was a store-level test bypassing the AI-service `@PreDestroy` lifecycle. Reworded to the correct mechanism.
- **Built (this session):** `.claude/hooks/pre-delete-guard.sh` (PreToolUse/Bash, wired in `settings.json`) — advisory, non-blocking §8 reminder fired only when `rm`/`git rm` targets a lifecycle-bearing `src/main/**.java` file (CDI scope / `@Produces` / `@PreDestroy` / `@Observes` / `ChatMemory*` / `*Store`). Tested 4 cases: warns on the lifecycle file, silent on plain/test/non-deletion. Daniel chose warn-not-block. Deterministic backstop remains the CI e2e test. (Applies next session — hooks load at startup.)
- **Pipelines reviewed, no change needed:** `ci.yml` runs `./gradlew spotlessCheck` + `test` on every `pull_request`; the new `TelegramConversationMemoryTest` already runs there, so a cross-request memory regression turns CI red without any workflow edit.
- **Model swap:** `gemini-2.5-flash` → `gemini-3.1-flash-lite` (cost). Tests use a mock model + `test-key`, so the gate does not exercise the live id — confirm it resolves with one real Telegram turn (same Plan-1 404 trap that hit `gemini-2.0-flash`).

### 2026-05-30 — Memory root-caused, refactored to the minimal correct fix (systematic debugging)
- **Resolved** the prior session's open follow-up. Root cause, confirmed in `quarkus-langchain4j-core:1.9.2` bytecode **and** the official docs: `@RegisterAiService` defaults to `@RequestScoped`; `TelegramBotRunner.handle()` terminates a request context per update, firing the generated bean's `@PreDestroy` → `QuarkusAiServiceContext.close()` → `ChatMemoryService.clearAll()` → `ChatMemory.clear()` → `ChatMemoryStore.deleteMessages(sessionId)`. The session is **wiped from the store at the end of every update**, regardless of store implementation.
- **Load-bearing fix = `@ApplicationScoped` on `Assistant`** (not the custom store). Proven by an end-to-end test (`TelegramConversationMemoryTest`: fake `ChatModel` via `QuarkusMock.installMockForType`, two turns across separate request contexts) run as a one-variable matrix: **B** = `@ApplicationScoped` + default store → PASS; **D** = default `@RequestScoped` + default store → FAIL (turn 2 loses turn 1). B vs D isolates scope; A vs B isolates store.
- **Refactor (better than the original fix):** deleted `AppScopedChatMemoryStore` (dead weight — a duplicate of the extension's `InMemoryChatMemoryStore`, wiped by `deleteMessages` just the same); kept `@ApplicationScoped Assistant` + the default store. Deleted `ChatMemoryPersistenceTest` (it passed even when end-to-end memory was broken → false confidence). Recorded the decision in [ADR 0006](adr/0006-application-scoped-ai-service-for-memory.md). Net vs `76dfeaf`: one fewer class, a real e2e regression guard, scope documented inline so it is not "cleaned up" again.
- **Test gate:** `./gradlew test` → BUILD SUCCESSFUL, 26 tests, zero failures (10 `TelegramCommandsTest` + 1 `AssistantMemoryWiringTest` + 2 `TelegramBotRunnerResetTest` + 3 `TelegramConversationMemoryTest` + 10 existing).
- **Live verification:** the **shipped** config **B** (custom store deleted, `@ApplicationScoped Assistant` + default store) was live-confirmed by Daniel on 2026-05-30 — two-turn memory persists and `/reset` clears, end-to-end through real Telegram + Gemini. Matches the e2e test prediction.

