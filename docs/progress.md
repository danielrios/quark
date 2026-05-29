# Task Progress Ledger

Mutable agent state. The MVP design lives in
[`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](./superpowers/specs/2026-05-25-agent-runtime-mvp.md);
do not duplicate it here. Once a Plan 1 file exists under
`docs/superpowers/plans/`, point at it instead. This file holds only:
current task pointer, session counter, last ~3 trajectory entries, and
live stack traces.

## Current Task
- Plan 1 — Telegram + Gemini walking skeleton — **DONE, merged to `main`** (PR #11 @ merge `8dc5c84`; release-please cut 0.2.0). Live end-to-end verified: Telegram long-poll → Gemini → reply.
- **Next: Plan 2 — in-process working memory + `/reset` command** (ADR 0003 scope table; no abstractions, memory on the dispatcher). Plan file not yet authored — write it via writing-plans before executing.

## System State
- Current Session Attempts: 0 / 3
- Git Branch: see `git branch --show-current`
- **Test gate fallback active:** `quarkus_callTool devui-testing_runTests` cannot detect HTTP port (persistent MCP bug across all stop/start cycles). Using `./gradlew test` as gate — harness pre-approved, Java 25 confirmed available.
- **Last gate (Plan 1 close-out):** `./gradlew test` → BUILD SUCCESSFUL, 10 tests, zero failures. Gotcha: keep dev mode off port 8081 — it collides with Quarkus's default test port and fails `@QuarkusTest` binding.

## Active Trajectory Logs / Error Traces
<!-- Append the most recent entry at the top. Trim older entries on each session — they live in the git log and PR descriptions, not here. -->

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
