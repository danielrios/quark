# Plan 5 — NIM Provider, Per-Session Preference, `/provider` + `/status`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exercise the `ModelGateway` SPI with a second real implementation — NVIDIA NIM via its OpenAI-compatible endpoint — and make the runtime's "resolve provider" stage real: per-session preference (`ProviderPreferenceStore`), resolution precedence, and the `/provider` + `/status` Telegram commands.

**Normative source:** [spec 2026-07-10](../specs/2026-07-10-plan-5-nim-provider.md) — exact contracts, reply strings, config keys, and decisions live there; this plan sequences the work. Options/rejections: [brainstorm](../brainstorms/2026-07-10-plan-5-nim-provider-brainstorm.md). Decisions land in **ADR 0008**.

**Architecture:** `provider.nim.NimModelGateway` wraps a named `@ModelName("nim") StreamingChatModel` (quarkus-langchain4j-openai, `base-url` → NIM). `ModelGateway` grows `String name()`; `AgentRuntime` selects the gateway per turn via `@Any Instance<ModelGateway>` — precedence `TurnRequest.provider` → `ProviderPreferenceStore` (new SPI, `memory.preference`) → `quark.provider.default`. Static misconfig fails startup; dynamic unknown names fail the turn (`TurnStarted` → `TurnFailed`, before memory load). `ModelInvoked` gains `provider`. Adapter writes preference directly (boundary-table slot), reads facts through three new runtime query methods.

**Tech Stack:** existing stack + `io.quarkiverse.langchain4j:quarkus-langchain4j-openai` (implementation, BOM-managed → 1.9.2) + `org.wiremock:wiremock` (testImplementation).

**Test gate:** the quarkus-agent MCP `devui-testing_runTests` per CLAUDE.md §3, after every task. When the MCP is unavailable in a session, the documented fallback is `./gradlew test` (recorded in `docs/progress.md`; the 2026-07-10 remote environment additionally substitutes system Gradle 8.14.3 + JDK 25 toolchain because the wrapper distribution is egress-blocked — exact command in the ledger). CI always runs the canonical `./gradlew test`. Baseline on post-Plan-4 main: **53 tests, 0 failures**.

**Branch:** Plan 5 work goes on a **fresh branch cut from post-merge main** — never stacked on merged Plan 4 history. (This authoring session's branch name, `claude/plan-4-runtime-extraction-zsqvkp`, is fixed by the remote harness; it was restarted from `origin/main` per the merged-PR rule, so the name is stale but the history is fresh. A human executor picks a fresh `plan-5-nim-provider` name instead.) Task 0 verifies the branch tip equals `origin/main` before any commit.

**Gate-critical sequencing (spec §5):** a second unqualified `ModelGateway` bean makes every unqualified injection ambiguous → deployment fails suite-wide. `name()` + runtime `Instance` migration land **before** `NimModelGateway` exists (Tasks 1–3 before Task 4).

---

## File Map

| File | Status | Responsibility |
|------|--------|----------------|
| `src/main/java/com/quark/provider/ModelGateway.java` | Modify | `+ String name()` |
| `src/main/java/com/quark/provider/gemini/GeminiModelGateway.java` | Modify | `name() → "gemini"` |
| `src/main/java/com/quark/memory/preference/ProviderPreferenceStore.java` | **Create** | SPI: `get` / `set` |
| `src/main/java/com/quark/memory/preference/InMemoryProviderPreferenceStore.java` | **Create** | `@ApplicationScoped`, `ConcurrentHashMap` |
| `src/main/java/com/quark/runtime/AgentRuntime.java` | Modify | `Instance<ModelGateway>` + resolution + startup validation + `providers()` / `resolveProvider()` / `messageCount()` |
| `src/main/java/com/quark/core/AgentEvent.java` | Modify | `ModelInvoked(turnId, provider)` |
| `src/main/java/com/quark/provider/nim/NimModelGateway.java` | **Create** | `@ModelName("nim")` model; `name() → "nim"`; same bridge/cancellation semantics as Gemini (duplication is a decision — spec §3.5) |
| `src/main/java/com/quark/adapter/telegram/TelegramCommands.java` | Modify | `Parsed(command, argument)`; `PROVIDER`, `STATUS`; normalization |
| `src/main/java/com/quark/adapter/telegram/TelegramBotRunner.java` | Modify | route `≠ CHAT` → dispatch; `/provider` + `/status` replies; startup `Instant` |
| `src/main/java/com/quark/adapter/telegram/TelegramMessages.java` | Modify | `formatUptime(Duration)` |
| `src/main/resources/application.properties` | Modify | spec §3.7 block |
| `build.gradle.kts` | Modify | two new deps |
| `docs/adr/0008-named-providers-and-preference-resolution.md` | **Create** | spec §6 |
| `src/test/java/com/quark/memory/preference/InMemoryProviderPreferenceStoreTest.java` | **Create** | store contract |
| `src/test/java/com/quark/provider/nim/NimModelGatewayTest.java` | **Create** | fake-model bridge test |
| `src/test/java/com/quark/provider/nim/NimWireMockResource.java` | **Create** | `QuarkusTestResourceLifecycleManager` (`restrictToAnnotatedClass = true`) |
| `src/test/java/com/quark/provider/nim/NimModelGatewayWireMockTest.java` | **Create** | wire path: auth header, SSE tokens, model name |
| `src/test/java/com/quark/provider/gemini/GeminiModelGatewayTest.java` | Modify | `name() == "gemini"` case (Task 1) |
| `src/test/java/com/quark/adapter/telegram/TelegramMessagesTest.java` | Modify | `formatUptime` cases (Task 6) |
| `src/test/java/com/quark/runtime/AgentRuntimeTest.java` | Modify | fakes gain `name()`; resolution/precedence/failure cases |
| `src/test/java/com/quark/runtime/RuntimeWiringTest.java` | Modify | `@Any Instance<ModelGateway>`; asserts both names (Task 4) |
| `src/test/java/com/quark/adapter/telegram/TelegramCommandsTest.java` | Modify | new parse cases; mechanical return-type migration |
| `src/test/java/com/quark/adapter/telegram/TelegramBotRunnerCommandsTest.java` | **Create** | all six reply rows (spec §3.6) |
| `src/test/java/com/quark/adapter/telegram/TelegramProviderSwitchTest.java` | **Create** | composed path: `/provider nim` → next turn hits nim model |

Untouched: memory backstops (`TelegramConversationMemoryTest`, `TelegramStreamingMemoryTest` — textually unchanged, re-verified green every task), `TelegramStreamHandler`(+tests), `ChatMemoryStore`/impl, `GreetingResource`.

---

## Task 0: Baseline gate + docs

- [ ] **0.1** Branch check: working branch is freshly based on post-merge `origin/main` (`git merge-base --is-ancestor` / tip comparison) — see the Branch note above. Gate green; record count (expect 53/0/0).
- [ ] **0.2** Brainstorm + spec + this plan committed; `docs/progress.md` Current Task → this plan (+ environment fallback note for the gate).
- [ ] **0.3** Commit: `docs: author plan 5 — NIM provider + preference resolution (brainstorm, spec, plan)`

## Task 1: `ModelGateway.name()` (single provider — no ambiguity yet)

- [ ] **1.1** Test first: `GeminiModelGatewayTest` asserts `name().equals("gemini")`; `AgentRuntimeTest.FakeGateway` gains `name() → "fake"` (compile-driven).
- [ ] **1.2** Add `String name()` to the SPI (javadoc: stable lower-case id — config/commands/preference/events/metrics); implement in `GeminiModelGateway`.
- [ ] **1.3** Gate green. Commit: `feat(provider): ModelGateway.name() — stable provider id (TDD)`

## Task 2: `memory.preference` SPI + in-memory store (TDD)

- [ ] **2.1** Test first: `InMemoryProviderPreferenceStoreTest` — empty get, set/get round-trip, overwrite, session isolation.
- [ ] **2.2** Implement SPI + `@ApplicationScoped InMemoryProviderPreferenceStore` (no `delete` — nothing needs it; `/reset` keeps preference by decision, spec §3.2).
- [ ] **2.3** Gate green. Commit: `feat(memory): ProviderPreferenceStore SPI + in-memory impl (TDD)`

## Task 3: Runtime resolution (TDD; still one gateway — suite must stay green)

- [ ] **3.1** Test first, `AgentRuntimeTest` (fakes as CDI-free instances). Test seam pinned: the `@Inject` constructor takes `@Any Instance<ModelGateway>` (spec §3.3) and delegates to a **package-private list-backed constructor** `(ChatMemoryStore, List<ModelGateway>, ProviderPreferenceStore, String)` — tests use the latter; no ~10-method `Instance` stub of `UnsupportedOperationException`s. Cases:
  - precedence: explicit `TurnRequest.provider` > stored preference > default;
  - `ModelInvoked.provider` = resolved name (event field added here);
  - dynamic unknown (stale preference / bogus explicit): `TurnStarted` → `TurnFailed("unknown provider: …")`, **no memory load, no gateway call, nothing persisted**;
  - `providers()` (sorted — deterministic rendering), `resolveProvider(sessionId)` (preference→default only, no validity check), `messageCount(sessionId)`; blank-but-present `TurnRequest.provider` → unknown-name failure;
  - validation logic: duplicate `name()`s / default-not-present → exception (unit-test the package-private `validate()` directly — least-power vehicle; the `StartupEvent` observer wiring is one line).
- [ ] **3.2** Implement: constructor `(ChatMemoryStore, @Any Instance<ModelGateway>, ProviderPreferenceStore, @ConfigProperty("quark.provider.default") String)`; resolution before memory load; `ModelInvoked(turnId, provider)`; startup observer calls `validate()`; log resolved provider with `turnId`. Add `quark.provider.default=gemini` to `application.properties`.
- [ ] **3.3** Migrate `RuntimeWiringTest` to `@Any Instance<ModelGateway>` (asserts `"gemini"` present). Memory backstops re-verified (no text change).
- [ ] **3.4** Gate green. Commit: `feat(runtime): provider resolution — explicit > preference > default (TDD)`

## Task 4: `NimModelGateway` + config (second bean lands safely now)

- [ ] **4.1** Add `quarkus-langchain4j-openai` to `build.gradle.kts`; add the spec §3.7 property block (including `%test` api-key and `timeout=60s`).
- [ ] **4.2** Confirmatory check (spec §3.7 — the `${NVIDIA_API_KEY:dummy}` fallback already sidesteps the unverified empty-value state): prod-profile boot without `NVIDIA_API_KEY` does not fail startup (lazy client). **Vehicle (harness-legal, non-interactive):** `./gradlew build -x test` (pre-approved), then background `QUARK_TELEGRAM_ENABLED=false java -jar build/quarkus-app/quarkus-run.jar` with a timeout, assert the `started in` log line, kill. (Acceptable alternative if the quarkus-agent MCP is available: dev-mode boot observation — the lazy-client behavior is profile-independent.) Record the observation in ADR 0008.
- [ ] **4.3** Test first: `NimModelGatewayTest` (fake `StreamingChatModel`): token order/completion/failure propagation, role mapping, `name() == "nim"`.
- [ ] **4.4** Implement `provider.nim.NimModelGateway`: constructor-injects `@ModelName("nim") StreamingChatModel`; same emitter bridge + cancellation-semantics javadoc as Gemini (duplication is the recorded decision).
- [ ] **4.5** `RuntimeWiringTest`: assert both `"gemini"` and `"nim"` resolve via `Instance`.
- [ ] **4.6** Gate green — **the whole suite**, this is the commit that would have broken it without Tasks 1–3. Commit: `feat(provider): NimModelGateway — NVIDIA NIM via OpenAI-compatible named model (TDD)`

## Task 5: WireMock wire test

- [ ] **5.1** Add `org.wiremock:wiremock` (testImplementation). `NimWireMockResource implements QuarkusTestResourceLifecycleManager`: starts WireMock on a random port, overrides `quarkus.langchain4j.openai.nim.base-url=http://localhost:<port>/v1`; registered with `restrictToAnnotatedClass = true` (config must not leak into the rest of the suite; isolated-restart cost accepted — spec §5.3).
- [ ] **5.2** `NimModelGatewayWireMockTest` (`@QuarkusTest`): stub `POST /v1/chat/completions` with `Content-Type: text/event-stream` returning an OpenAI-compatible SSE body (two chunks + `[DONE]`); assert `Authorization: Bearer test-key`, request `model` = configured id, tokens arrive in order, Multi completes. Fidelity limit (accepted): WireMock serves the body in one write — this proves config/auth/SSE-parsing, not incremental delivery; stream semantics are covered from the other side by the fake-model unit test (Task 4.3).
- [ ] **5.3** Gate green. Commit: `test(provider): WireMock wire test for the NIM OpenAI-compat path`

## Task 6: Telegram commands (TDD)

- [ ] **6.1** Test first: `TelegramCommandsTest` — new cases `/provider`, `/provider nim`, `/provider@bot nim`, `/provider NIM` (normalized), `/provider nim extra` (first token wins), `/status`, plus mechanical migration of existing cases to `Parsed`.
- [ ] **6.2** Implement `Parsed(Command, Optional<String> argument)` + `PROVIDER`/`STATUS` + `trim().toLowerCase(Locale.ROOT)` normalization of the argument.
- [ ] **6.3** Test first: `TelegramBotRunnerCommandsTest` — all **six** reply rows of spec §3.6 verbatim (incl. `/provider gemini` = default → no hint), deterministic via sorted `providers()` + the package-private start-instant seam; `TelegramMessagesTest` gains `formatUptime` cases (`0m`, `12m`, `3h 12m`, `2d 0h 5m`).
- [ ] **6.4** Implement: `TelegramBotRunner` captures startup `Instant` (package-private seam for tests); `handle()` routes `command != CHAT` → `dispatch` (parse once); `dispatch` gains PROVIDER (bare/set-with-conditional-hint/bogus via `runtime.providers()` + preference store write) and STATUS (`formatUptime` + `resolveProvider` + `messageCount`); replies clamped as today.
- [ ] **6.5** Composed-path test `TelegramProviderSwitchTest` (`@QuarkusTest`): two recording models — default bean + `@ModelName("nim")` via `QuarkusMock.installMockForType(instance, StreamingChatModel.class, new ModelName.Literal("nim"))` (`ModelName$Literal` verified present in quarkus-langchain4j-core 1.9.2; fallback if resolution surprises: a 3-line `AnnotationLiteral<ModelName>` in the test tree); `dispatch("/provider nim")`, then a chat turn; assert the nim-side recorder got the prompt (two unit tests on either side of a seam don't prove the seam — §8 altitude lesson).
- [ ] **6.6** Gate green. Commit: `feat(telegram): /provider and /status commands over runtime queries (TDD)`

## Task 7: ADR 0008 + docs close-out

- [ ] **7.1** Author `docs/adr/0008-named-providers-and-preference-resolution.md` per spec §6, including the doc-check citations (embedded config docs of quarkus-langchain4j 1.9.2: `quarkus.langchain4j.chat-model.provider`, `quarkus.langchain4j."model-name".chat-model.provider`, `quarkus.langchain4j.openai."model-name".{base-url,api-key,chat-model.model-name,timeout}`, provider ids `ai-gemini`/`openai`, `@ModelName` + `ModelName.Literal`, openai artifact on Central) and the Task 4.2 blank-key finding.
- [ ] **7.2** `README.md`: `/provider` + `/status` commands, `NVIDIA_API_KEY`/`NIM_MODEL`/`NIM_BASE_URL` env vars, provider list. `ARCHITECTURE.md`: Bridge table row 5 landed. `docs/progress.md`: trajectory entry — gate counts; verification honesty: **no live NIM smoke from this environment** (endpoint egress-blocked) — WireMock + composed-path test locally, live Telegram smoke is a post-merge user step.
- [ ] **7.3** `spotlessApply` + final gate green. Commit: `docs: ADR 0008 + README/architecture/progress for plan 5`
- [ ] **7.4** Push the Plan 5 branch (`git push -u origin <branch>` — this session: the harness-designated branch per the Branch note; a human executor: their fresh `plan-5-nim-provider` branch).

---

## Explicitly OUT of scope

| Deferred | Owner |
|----------|-------|
| Failover, provider health checks, `ProviderResolved` events | future (vision) |
| REST/SSE adapter; sanitizing `TurnFailed.reason` for external clients | Plan 6 (ADR 0007 trigger) |
| ArchUnit rules; Micrometer metrics (`ModelInvoked.provider` as tag) | Plan 7 |
| Persistent preference store; `ProviderPreferenceStore.delete` | future, on demand |
| Per-provider system prompts / model params | not demanded |

## Done criteria

- Gate zero failures/errors on every task commit; final suite ≈ 70+ tests.
- Memory backstops textually unchanged and green (§8).
- `/provider` + `/status` behave exactly per spec §3.6 reply table.
- A stored `nim` preference demonstrably routes the next turn to the NIM-side model (composed-path test).
- Boundary spot-check: `adapter.*` imports nothing from `provider.*`/langchain4j; langchain4j only in `provider.gemini` + `provider.nim`; `runtime` imports only the SPI (ArchUnit lands Plan 7).
- ADR 0008 committed. Plan 6/7 items not implemented (WIP = 1).

## Rollback

Each task is one green commit — `git revert` independently. Task 4 is the first commit where a second gateway bean exists; if it destabilizes the suite in an unforeseen way, reverting Task 4+ leaves Tasks 1–3 (pure internal generalization) safely in place. Branch-level rollback: don't merge the PR.

## Self-Review

- [ ] Every §3 spec contract implemented verbatim (reply strings, event sequence, precedence, normalization).
- [ ] Sequencing constraint honored: no commit exists where two unqualified-injectable gateways coexist with an unqualified injection point.
- [ ] ADR 0002 boundary table satisfied for every new/modified import.
- [ ] No behavioral assertion weakened in migrated tests; memory backstops untouched.
- [ ] Failure paths tested: unknown dynamic provider, gateway stream failure on NIM path (unit), blank-key decision recorded.
