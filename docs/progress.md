# Task Progress Ledger

Mutable agent state. The MVP design lives in
[`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](./superpowers/specs/2026-05-25-agent-runtime-mvp.md);
do not duplicate it here. This file holds only: current task pointer, session
counter, last ~3 trajectory entries, and live stack traces.

## Current Task
- **Phase 0 — restore green baseline — DONE (2026-08-23).** The unfinished Plan 5 experiment (NIM provider + provider preference) was removed from the active tree and intentionally abandoned rather than carried forward. The runtime returned to the pre-Plan-5 baseline; `provider/nim/`, test `provider/nim/`, and the stray root `io/` artifact are gone. Restored baseline verified: **53 tests, 0 failures** (= Plan 4 close-out count), `spotlessCheck` + `build` green.
- **Next: Migration 1 — Kotlin and Coroutines build support** ([plan](./superpowers/plans/2026-08-23-migration-1-kotlin-coroutines-build-support.md); ADR 0008). Build-only; no runtime migration; no production Kotlin.
- Roadmap after that (sequencing labels, not designs): Migration 2 — lock streaming/cancellation semantics (G-1/G-2) → Migration 3 — framework-neutral Kotlin contracts → later: runtime impl, framework-ownership removal, host integrations. See ADR 0008 §5 and ARCHITECTURE.md "Near-term migration direction".
- Plan 5 status: abandoned before completion. NIM remains only a candidate future second provider after the neutral provider boundary; its old provider-selection design is not carried forward. Plans 6–7: historical, re-evaluate.

## System State
- Current Session Attempts: 0 / 3
- **Last gate (Phase 0, 2026-08-23):** `./gradlew test` → BUILD SUCCESSFUL, **53 tests, 0 failures, 0 errors** (matches Plan 4 close-out exactly); `./gradlew spotlessCheck` → green; `./gradlew build` → BUILD SUCCESSFUL.
- **Test gate fallback active:** `quarkus_callTool devui-testing_runTests` cannot detect HTTP port (persistent MCP bug across all stop/start cycles). Using `./gradlew test` as gate — harness pre-approved, Java 25 confirmed available.

## Active Trajectory Logs / Error Traces
<!-- Append the most recent entry at the top. Trim older entries on each session — they live in the git log and PR descriptions, not here. -->

### 2026-08-23 — Phase 0: Plan 5 abandoned, baseline restored green
- Classified every modified/untracked file before touching anything: Group A (keep) = migration-direction docs; Group B (remove) = 8 modified files + `provider/nim/` + test `provider/nim/`; Group C = stray root `io/quarkiverse/langchain4j/ModelName.java` (vendored `@ModelName` annotation copy outside any source root — never compiled); Group D = unrelated local agent tooling, kept outside the migration work.
- Plan 5 decision: the NIM/provider-preference experiment was intentionally discarded after review. It was small, incomplete, non-compiling, and deepened CDI coupling (`Instance`, `@Named`, `@ConfigProperty`) inside orchestration. Carrying that code forward would create more migration/rework cost than value. The useful intent survives only as a future possibility: NIM may be reimplemented from scratch after the neutral provider boundary exists.
- Restoration: `git restore` returned the Plan 5-modified tracked files to HEAD (including runtime javadoc the WIP had stripped and `gemini-3.1-flash-lite` config); `io/` + `nim/` trees were removed. Verified: no src/build deltas vs the pre-Plan-5 baseline.
- Gates re-run on restored tree: `test` 53/0/0 · `spotlessCheck` · `build` — all green. No Kotlin, no architecture, no behavior change.

### 2026-08-23 — ADR 0008: framework-independent runtime + Kotlin/JVM migration
- Verified code before deciding (not just the new docs): runtime seams (`AgentRuntime`, `AgentEvent` 7 variants, `TurnRequest`, `ModelGateway Multi<String>`, `ChatMemoryStore`), CDI/Mutiny/`@ConfigProperty`/quarkus-logging coupling points, Telegram adapter lifecycle, ~50-test behavioral suite.
- Created **ADR 0008** (`docs/adr/0008-framework-independent-runtime-and-kotlin-migration.md`): decisions D1–D9, increments (Phase 0 + Migrations 1–5 + later), protected current-runtime invariants, gaps G-1…G-4, milestone acceptance criteria. Supersedes ADR 0002 **in part** only; 0001/0003/0006/0007 carried forward.
- Created the **Migration 1 plan** (Kotlin + coroutines build support; test-tree-only Java/Kotlin interop proof; earned-dependency non-goals: no `quarkus-kotlin`, no `kotlinx-serialization`, no `kotlinx-coroutines-test` yet).
- Doc updates: ADR index (added missing 0007 row + 0008), ADR 0002 superseded-in-part status note, ARCHITECTURE.md pointers.
- Discovered then: working tree non-compiling (uncommitted Plan-5 work) — subsequently resolved by Phase 0 above.

### 2026-07-04 — Plan 4 implementation complete — runtime extraction
- branch: plan-4-runtime-extraction (Tasks 0–8 each one green commit; c83fd12 → docs close-out)
- What landed: `core` (sealed `AgentEvent`, `TurnRequest`, `ChatMessage`), `memory` (`ChatMemoryStore` SPI + bounded `@ApplicationScoped` in-memory impl), `provider` (`ModelGateway` SPI + `GeminiModelGateway` over the injectable `StreamingChatModel` bean), `runtime.AgentRuntime` (persist-on-success-only, failures-as-events, one terminal event), Telegram cut over to event projection, **`Assistant` retired under §8 in its own commit with ADR 0007**, packages moved to `adapter.telegram`.
- Gate: `./gradlew test` — **53 tests, 0 failures** at close-out (baseline was 32). Verification honesty (§8): integration tests at the real request-context boundary, not live-smoked.
