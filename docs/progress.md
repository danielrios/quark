# Task Progress Ledger

Mutable agent state. The MVP design lives in
[`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](./superpowers/specs/2026-05-25-agent-runtime-mvp.md);
do not duplicate it here. Once a Plan 1 file exists under
`docs/superpowers/plans/`, point at it instead. This file holds only:
current task pointer, session counter, last ~3 trajectory entries, and
live stack traces.

## Current Task
- Plan 2 — in-process working memory + `/reset` — **DONE** (commits `18bd893`–`8e8f5e8`, branch `worktree-plan-2-working-memory-reset`). Per-session memory via `@MemoryId` on an `@ApplicationScoped` `Assistant` (see [ADR 0006](adr/0006-application-scoped-ai-service-for-memory.md)); `/reset` clears session via `ChatMemoryStore.deleteMessages`. 26 tests, zero failures.
- **Next: Plan 3 — Telegram streaming via throttled message edits** (ADR 0003); plan file not yet authored.

## System State
- Current Session Attempts: 0 / 3
- Git Branch: see `git branch --show-current`
- **Test gate fallback active:** `quarkus_callTool devui-testing_runTests` cannot detect HTTP port (persistent MCP bug across all stop/start cycles). Using `./gradlew test` as gate — harness pre-approved, Java 25 confirmed available.
- **Last gate (Plan 1 close-out):** `./gradlew test` → BUILD SUCCESSFUL, 10 tests, zero failures. Gotcha: keep dev mode off port 8081 — it collides with Quarkus's default test port and fails `@QuarkusTest` binding.

## Active Trajectory Logs / Error Traces
<!-- Append the most recent entry at the top. Trim older entries on each session — they live in the git log and PR descriptions, not here. -->

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

### 2026-05-29 — Plan 2 complete (branch `worktree-plan-2-working-memory-reset`)
- **Shipped:** per-session conversation memory via `@MemoryId String sessionId` on `Assistant.chat()` — LangChain4j's built-in `ChatMemoryProvider` supplies a bounded `MessageWindowChatMemory` per session over the default in-memory store, zero new classes. `/reset` wired in `TelegramBotRunner.dispatch()` via `ChatMemoryStore.deleteMessages(sessionId)`, returns "Memory cleared. Starting fresh."
- **Spec §6.2 deviation:** plan used LangChain4j built-in memory instead of the spec's suggested hand-rolled `ChatMemory`/`Map` approach. Rationale: strictly less code, idiomatic Quarkus, within ADR 0003's no-abstractions envelope, and the spec hedged its shape ("suggested structure"). Zero bespoke storage classes introduced.
- **Task 4 dev-mode verification:** deferred — MCP `quarkus_start` stuck in "starting" state with empty log output after ~5 minutes; no crash, no error. Automated tests cover all functional assertions; the live Telegram smoke test is blocked on MCP dev-mode start reliability, not on correctness.
- **Test gate:** `./gradlew test` → BUILD SUCCESSFUL, 24 tests, zero failures (10 `TelegramCommandsTest` + 1 `AssistantMemoryWiringTest` + 2 `TelegramBotRunnerResetTest` + 1 `ChatMemoryPersistenceTest` + 10 existing).

### 2026-05-29 — Plan 1 closed out: PR #11 merged to `main`
- PR #11 merged (`8dc5c84`); release-please cut 0.2.0. Two post-review fixes landed: clamp Telegram replies to 4096 chars (`TelegramMessages.clampToTelegramLimit`, +3 TDD tests) and `gemini-2.0-flash` → `gemini-2.5-flash` (old model 404s for newly issued API keys).
- Live verified end-to-end by the user: real Telegram message → Gemini reply. A persistent 409 seen during testing was root-caused to a stale duplicate quark poller (killed) — not a code bug; hermes-agent runs on a separate bot token.
- Deferred (Minor, by design): capped backoff on persistent poll failure, `ok == false` vs empty batch, daemon-shutdown comment. Candidates for a small poll-loop hardening pass, not Plan 1.

### 2026-05-29 17:03Z — PR #11 review fix: clamp Telegram replies to 4096-char limit (pure fn + TDD) and mark planned README scope items; ./gradlew test + spotlessCheck green (10 tests)
- branch: claude/plan-1-telegram-gemini-skeleton
- status: Done — addressed the one Important finding from PR #11 review (silent drop of >4096-char Gemini replies). Added pure `TelegramMessages.clampToTelegramLimit` (TDD: 3 new tests), wired into `TelegramBotRunner.handle()`; marked unimplemented README "MVP scope / In" items as _(planned)_. Next: commit + push so PR #11 updates.

### 2026-05-28 — Plan 1 complete (branch `claude/plan-1-telegram-gemini-skeleton`)
- All tasks 0–5 executed. 7 automated tests pass (BUILD SUCCESSFUL).
- Smoke test: Telegram → poller → Gemini reached and authenticated. Free-tier quota exhausted on key provided; architecture is verified end-to-end.
- One unplanned fix: `ContextNotActiveException` on `Assistant` call from virtual thread — fixed by programmatic CDI request context activation per message in `TelegramBotRunner.handle()`.
- Test gate fallback: `./gradlew test` throughout (MCP `callTool` port-detection bug persists).
