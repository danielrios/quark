# Task Progress Ledger

Mutable agent state. The MVP design lives in
[`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](./superpowers/specs/2026-05-25-agent-runtime-mvp.md);
do not duplicate it here. Once a Plan 1 file exists under
`docs/superpowers/plans/`, point at it instead. This file holds only:
current task pointer, session counter, last ~3 trajectory entries, and
live stack traces.

## Current Task
- Plan 4 — runtime extraction — **DONE & MERGED** ([PR #26](https://github.com/danielrios/quark/pull/26), released 0.5.0). 53 tests, 0 failures on main.
- **Current: Plan 5 — NIM provider + `/provider` + `/status`** (ADR 0003). Design trio authored 2026-07-10 through a 3-round Fable 5 critique loop:
  [brainstorm](superpowers/brainstorms/2026-07-10-plan-5-nim-provider-brainstorm.md) →
  [spec](superpowers/specs/2026-07-10-plan-5-nim-provider.md) (normative) →
  [plan](superpowers/plans/2026-07-10-plan-5-nim-provider.md). Implementation next, task by task per the plan.

## System State
- Current Session Attempts: 0 / 3
- Git Branch: see `git branch --show-current`
- **Test gate fallback active (this remote environment, 2026-07-10):** quarkus-agent MCP absent; additionally the Gradle 9.5.1 wrapper distribution and JDK downloads are egress-blocked (proxy policy — reported, not routed around). Sanctioned local gate: apt `openjdk-25-jdk-headless` + system Gradle 8.14.3 with a JDK 25 toolchain init script — `/opt/gradle/bin/gradle test -I <scratch>/toolchain25.init.gradle` (daemon on JDK 21, compile/test forked to 25). CI remains the canonical `./gradlew test`.
- **Last gate (Plan 5 baseline, post-Plan-4 main):** 53 tests, zero failures, zero errors. Gotcha: keep dev mode off port 8081 — it collides with Quarkus's default test port and fails `@QuarkusTest` binding.

## Active Trajectory Logs / Error Traces
<!-- Append the most recent entry at the top. Trim older entries on each session — they live in the git log and PR descriptions, not here. -->

### 2026-07-10 — Plan 5 design trio authored via Fable 5 critique loop
- branch: `claude/plan-4-runtime-extraction-zsqvkp` restarted from post-merge main (merged-PR rule).
- **Process:** brainstorm → spec → plan, each round-tripped through a persistent Fable 5 critic session. Round 1 (brainstorm): 15 findings — incl. a real blocker (a second `ModelGateway` bean makes every unqualified injection ambiguous → suite-wide deployment failure; drove the plan's Tasks 1–3-before-4 sequencing). Round 2 (spec): brainstorm confirmed fully resolved; 11 minor/nit spec findings (reply-string determinism, config landing order, `${NVIDIA_API_KEY:dummy}`) — all applied. Round 3 (plan): spec re-verified fully converged; plan got 1 major (no branch-creation step — would have stacked Plan 5 on merged Plan 4 history) + 3 minor + 4 nit, all applied; gate-greenness verified task-by-task by the critic; final verdict **ready to drive implementation**.
- **Docs-first (§8) with blocked docs site:** `docs.quarkiverse.io` egress-blocked; config claims verified instead against the extensions' *embedded* config docs (`META-INF/quarkus-config-doc/quarkus-config-model.json`, quarkus-langchain4j 1.9.2): named-model pattern, provider ids `ai-gemini`/`openai`, `@ModelName`(+`Literal`), per-model `base-url`/`api-key`, and the **10 s default client timeout** (raised to 60 s in the spec — would have caused mysterious mid-stream failures). Citations land in ADR 0008.
- **No live NIM smoke possible here** (`integrate.api.nvidia.com` egress-blocked): local proof = WireMock wire test + composed-path `@QuarkusTest`; live Telegram/NIM smoke is a post-merge user step.

### 2026-07-04 — Plan 4 implementation complete — runtime extraction
- branch: plan-4-runtime-extraction (Tasks 0–8 each one green commit; c83fd12 → docs close-out)
- What landed: `core` (sealed `AgentEvent`, `TurnRequest`, `ChatMessage`), `memory` (`ChatMemoryStore` SPI + bounded `@ApplicationScoped` in-memory impl), `provider` (`ModelGateway` SPI + `GeminiModelGateway` over the injectable `StreamingChatModel` bean), `runtime.AgentRuntime` (persist-on-success-only, failures-as-events, one terminal event), Telegram cut over to event projection, **`Assistant` retired under §8 in its own commit with ADR 0007**, packages moved to `adapter.telegram`.
- Gate: `./gradlew test` (quarkus-agent MCP unavailable this session; documented fallback) — **49 tests, 0 failures, 0 errors** at close-out (baseline was 32). Every prior behavioral assertion preserved; `TelegramConversationMemoryTest` kept its request-context-per-update altitude and passed unchanged after the deletion.
- Verification honesty (§8): behavior is **verified via integration tests at the real request-context boundary** (conversation + streaming memory guards) — *not* live-smoked against Telegram from this environment.
- Process: cavecrew delegation — sonnet builders (≤2-file scopes), opus reviewers per task (2 parallel on the cutover). Review yield: 1 test gap closed (persist-failure path), zero-token placeholder fix, explicit BUFFER strategy, window+1 nuance documented, javadoc corrections.

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


