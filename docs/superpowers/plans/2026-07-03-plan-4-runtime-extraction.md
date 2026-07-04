# Plan 4 — Runtime Extraction: `AgentRuntime`, `AgentEvent`, `ModelGateway`, `ChatMemoryStore`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the destination runtime seams (ADR 0001) from Plans 1–3's working code. After this plan, `AgentRuntime.execute(TurnRequest) : Multi<AgentEvent>` is the single orchestration point; Telegram is an adapter that projects the event stream; Gemini is a provider behind `ModelGateway`; memory is owned by the runtime behind a `ChatMemoryStore` SPI. No new user-visible behavior.

**Architecture:** Layered packages land as prescribed by ARCHITECTURE.md "Destination": `core` (pure JDK contracts: sealed `AgentEvent`, `TurnRequest`, `ChatMessage`), `runtime` (`AgentRuntime`), `memory` (`ChatMemoryStore` SPI + in-memory impl), `provider` (`ModelGateway` SPI) / `provider.gemini` (the only langchain4j-touching package), `adapter.telegram` (moved from `telegram`). Memory ownership moves from the langchain4j AI service into `AgentRuntime` — which retires `Assistant` (`@RegisterAiService`). That deletion is lifecycle-bearing (CLAUDE.md §8) and gets a dedicated commit plus **ADR 0007**, superseding the memory-ownership mechanics of ADR 0006 while carrying its invariant forward (app-scoped store + explicit per-session id). Failures become events (`TurnFailed`), never `onError()` (ADR 0001).

**Two decisions confirmed with the project owner (2026-07-03):**
1. **Retire `Assistant` in this plan** — `GeminiModelGateway` wraps the injectable `StreamingChatModel` bean; the runtime owns load/persist via the SPI.
2. **Core-owned message type** — `record ChatMessage(Role role, String text)` in `com.quark.core`; SPIs and runtime stay langchain4j-free per ADR 0002's boundary table; the gateway maps core → langchain4j at the provider boundary.

**Tech Stack:** Java 25, Quarkus 3.35.4, `io.smallrye.mutiny.Multi`, quarkus-langchain4j (confined to `provider.gemini`), JUnit 5 + Mockito (existing deps only — nothing new).

**Test gate:** `./gradlew test` after every task (quarkus-agent MCP unavailable this session; fallback documented in `docs/progress.md`). Baseline: 32 tests, 0 failures.

---

## File Map

| File | Status | Responsibility |
|------|--------|----------------|
| `src/main/java/com/quark/core/AgentEvent.java` | **Create** | Sealed interface + 7 nested record variants, all carrying `turnId` |
| `src/main/java/com/quark/core/TurnRequest.java` | **Create** | `(sessionId, Optional<String> provider, message)` + `of()` factory |
| `src/main/java/com/quark/core/ChatMessage.java` | **Create** | `(Role, text)` record; `Role { SYSTEM, USER, ASSISTANT }` |
| `src/main/java/com/quark/memory/ChatMemoryStore.java` | **Create** | SPI: `load` / `append` / `delete` |
| `src/main/java/com/quark/memory/InMemoryChatMemoryStore.java` | **Create** | `@ApplicationScoped`, `ConcurrentHashMap`, bounded (evict oldest > `quark.memory.max-messages`) |
| `src/main/java/com/quark/provider/ModelGateway.java` | **Create** | SPI: `Multi<String> stream(List<ChatMessage> history)` |
| `src/main/java/com/quark/provider/gemini/GeminiModelGateway.java` | **Create** | Maps core→langchain4j messages; bridges `StreamingChatResponseHandler` → `Multi` |
| `src/main/java/com/quark/runtime/AgentRuntime.java` | **Create** | `execute(TurnRequest)` pipeline + `reset(sessionId)` |
| `src/main/java/com/quark/telegram/TelegramStreamHandler.java` | Modify → move | Consume `Multi<AgentEvent>` via `instanceof` projection |
| `src/main/java/com/quark/telegram/TelegramBotRunner.java` | Modify → move | Inject `AgentRuntime` only; `dispatch` = RESET→`reset()`, CHAT→blocking collect |
| `src/main/java/com/quark/chat/Assistant.java` | **Delete** (Task 6) | Retired; see ADR 0007 |
| `src/main/resources/application.properties` | Modify | `+ quark.memory.max-messages=20`; `− quarkus.langchain4j.chat-memory.memory-window.max-messages` |
| `docs/adr/0007-agent-runtime-owns-conversation-memory.md` | **Create** | Deletion rationale + citations + invariant lineage from ADR 0006 |
| `src/test/java/com/quark/memory/InMemoryChatMemoryStoreTest.java` | **Create** | Plain JUnit store contract tests |
| `src/test/java/com/quark/provider/gemini/GeminiModelGatewayTest.java` | **Create** | Plain JUnit; fake `StreamingChatModel` |
| `src/test/java/com/quark/runtime/AgentRuntimeTest.java` | **Create** | Plain JUnit; hand-rolled fakes; event/persistence semantics |
| `src/test/java/com/quark/runtime/RuntimeWiringTest.java` | **Create** | Replaces `AssistantMemoryWiringTest` |
| `src/test/java/com/quark/telegram/*` (5 classes) | Modify → move | Adapted to runtime seams, **assertions preserved** |
| `src/test/java/com/quark/chat/RecordingChatModel.java` | **Delete** (Task 6) | Blocking model path gone |
| `src/test/java/com/quark/chat/RecordingStreamingChatModel.java` | Move | → `com.quark.provider.gemini` (test tree) |

All of `com.quark.telegram` moves to `com.quark.adapter.telegram` in Task 7. Untouched: `GreetingResource`(+Test/IT), `TelegramApi`, `TelegramCommands`(+Test), `TelegramMessages`(+Test), poll loop, throttle/clamp/placeholder behavior.

---

## Contracts (exact shapes)

```java
// com.quark.core.ChatMessage
public record ChatMessage(Role role, String text) {
    public enum Role { SYSTEM, USER, ASSISTANT }
}

// com.quark.core.TurnRequest — provider unused until Plan 5, part of the ADR 0001 contract
public record TurnRequest(String sessionId, Optional<String> provider, String message) {
    public static TurnRequest of(String sessionId, String message) {
        return new TurnRequest(sessionId, Optional.empty(), message);
    }
}

// com.quark.core.AgentEvent — sealed; every variant carries turnId; fields only what Plan 4 consumers need
public sealed interface AgentEvent {
    String turnId();
    record TurnStarted(String turnId, String sessionId) implements AgentEvent {}
    record MemoryLoaded(String turnId, int messageCount) implements AgentEvent {}
    record ModelInvoked(String turnId) implements AgentEvent {}
    record TokenEmitted(String turnId, String text) implements AgentEvent {}
    record ModelCompleted(String turnId) implements AgentEvent {}
    record TurnCompleted(String turnId, String text) implements AgentEvent {}
    record TurnFailed(String turnId, String reason) implements AgentEvent {}
}

// com.quark.memory.ChatMemoryStore
public interface ChatMemoryStore {
    List<ChatMessage> load(String sessionId);      // immutable copy; empty list if unknown session
    void append(String sessionId, ChatMessage message);
    void delete(String sessionId);
}

// com.quark.provider.ModelGateway
public interface ModelGateway {
    Multi<String> stream(List<ChatMessage> history);
}
```

**`AgentRuntime.execute(TurnRequest)`** (mirrors ARCHITECTURE.md Destination pipeline): `Multi.createFrom().deferred(...)` (lazy, per-subscription state) → `turnId = UUID` → `store.load(sessionId)` → emit `TurnStarted, MemoryLoaded(count), ModelInvoked` → prompt = `SYSTEM` constant (same text as today's `@SystemMessage`) + history + user message → `gateway.stream(prompt)` → each chunk accumulates + maps to `TokenEmitted` → on completion: persist `USER` + `ASSISTANT` messages, emit `ModelCompleted`, `TurnCompleted(text)` → any upstream failure recovers to a single `TurnFailed(reason)` item and the `Multi` completes normally. Guarantees: exactly one terminal event; failures are events; **nothing persisted on failure** (deliberate deviation — today langchain4j persists the user message even when the model call fails; recorded in ADR 0007). `reset(sessionId)` delegates to `store.delete` — lives on the runtime because ADR 0002 forbids adapters from touching chat memory directly.

**Telegram projection (ADR 0001 renderer discipline — `instanceof`, never exhaustive `switch`):** `TokenEmitted` → buffer + throttled edit; `TurnFailed` → `ERR_FALLBACK` edit; `TurnCompleted` → final flush; latch released on stream completion (defensive `onError` kept); 60 s timeout + cancel preserved.

---

## Task 0: Baseline gate + plan doc

- [x] **0.1** `./gradlew test` → **32 tests, 0 failures, 0 errors** (2026-07-03, local; recorded in progress ledger).
- [x] **0.2** This file committed; `docs/progress.md` Current Task points here.
- [x] **0.3** Commit: `docs: author plan 4 — runtime extraction`

## Task 1: `core` package

- [x] **1.1** Create the three `com.quark.core` types exactly as in Contracts. Zero non-JDK imports (no Quarkus, no langchain4j, no Mutiny, no Jackson).
- [x] **1.2** `./gradlew test` green (compilation is the check — pure records carry no behavior worth unit-testing).
- [x] **1.3** Commit: `feat(core): AgentEvent, TurnRequest, ChatMessage contracts`

## Task 2: `memory` SPI + in-memory store (TDD)

- [x] **2.1** Test first: `InMemoryChatMemoryStoreTest` (plain JUnit): load-unknown→empty; append/load round-trip preserves order; sessions isolated; delete clears only target; eviction: bound of N keeps the *newest* N (seed max-messages+2, assert oldest 2 gone); `load` returns a defensive copy (mutating it doesn't affect the store).
- [x] **2.2** Implement `InMemoryChatMemoryStore`: `@ApplicationScoped` (ADR 0006 invariant carried to the SPI era), `ConcurrentHashMap<String, List<ChatMessage>>`, bound from `@ConfigProperty quark.memory.max-messages` default 20 (constructor overload or setter for plain-JUnit testability).
- [x] **2.3** Add `quark.memory.max-messages=20` to `application.properties` (keeps Plan 2's `memory-window.max-messages=20` behavior; the langchain4j property is removed in Task 6).
- [x] **2.4** Gate green. Commit: `feat(memory): ChatMemoryStore SPI + bounded InMemoryChatMemoryStore (TDD)`

## Task 3: `provider` SPI + `GeminiModelGateway` (TDD)

- [x] **3.1** §8 docs-first check: confirm via quarkus-langchain4j docs (context7 MCP) that `ChatModel`/`StreamingChatModel` beans are produced from provider config and injectable **without any `@RegisterAiService`**. Record citation for ADR 0007.
- [x] **3.2** Test first: `GeminiModelGatewayTest` (plain JUnit, fake `StreamingChatModel`): chunks emitted in order then completion; model error → `Multi` failure (runtime maps it, not the gateway); role mapping core→langchain4j (`SYSTEM`→`SystemMessage`, `USER`→`UserMessage`, `ASSISTANT`→`AiMessage`).
- [x] **3.3** Implement `ModelGateway` SPI + `@ApplicationScoped GeminiModelGateway`: **constructor injection** of `StreamingChatModel`; `Multi.createFrom().emitter(..., BackpressureStrategy.BUFFER)` bridging `onPartialResponse`/`onCompleteResponse`/`onError`.
- [x] **3.4** Gate green. Commit: `feat(provider): ModelGateway SPI + GeminiModelGateway over StreamingChatModel (TDD)`

## Task 4: `runtime.AgentRuntime` (TDD)

- [x] **4.1** Test first: `AgentRuntimeTest` (plain JUnit, hand-rolled fake gateway/store; collect via `execute(req).collect().asList().await()`): happy-path event sequence `TurnStarted, MemoryLoaded, ModelInvoked, TokenEmitted×n, ModelCompleted, TurnCompleted`; same `turnId` on every event; `MemoryLoaded.messageCount` = history size; gateway receives system + history + user message in order; persistence = exactly user + assistant appended, in order, once; gateway failure mid-stream → single `TurnFailed`, normal completion, **zero appends**; store-load failure → `TurnFailed`; exactly one terminal event on every path; `reset` delegates to `store.delete`.
- [x] **4.2** Implement `AgentRuntime` per Contracts. Log start/terminal with `turnId`.
- [x] **4.3** Gate green. Commit: `feat(runtime): AgentRuntime — Multi<AgentEvent> execute(TurnRequest) (TDD)`

## Task 5: Cut Telegram over to the runtime

`Assistant` stays in the tree but nothing references it after this task — the commit is pure rewiring, deletion comes separately (§8).

- [x] **5.1** `TelegramStreamHandler`: inject `AgentRuntime`; same public signature; project events per Contracts.
- [x] **5.2** `TelegramBotRunner`: inject `AgentRuntime` only (drop `Assistant` + langchain4j `ChatMemoryStore`); `dispatch`: RESET → `runtime.reset(sessionId)` + `"Memory cleared. Starting fresh."`; CHAT → blocking collect of `execute(...)` → `TurnCompleted.text` else `ERR_FALLBACK`. `handle()`/poll loop/request-context lifecycle unchanged.
- [x] **5.3** Adapt tests, **assertions preserved**: `TelegramStreamHandlerTest` + `TelegramThrottleTest` (`@InjectMock AgentRuntime`, stub event `Multi`s; error case = `TurnFailed` item); `TelegramBotRunnerResetTest` (our store SPI); `TelegramConversationMemoryTest` (**§8/CI backstop — name, request-context-per-call altitude, assertions unchanged**; model swap `RecordingChatModel`→`RecordingStreamingChatModel` since the runtime path is streaming-only; javadoc updated to `dispatch → AgentRuntime → GeminiModelGateway → StreamingChatModel`); `TelegramStreamingMemoryTest` (drive `runtime.execute` twice, collect `TokenEmitted`); `AssistantMemoryWiringTest` → `runtime.RuntimeWiringTest` (`AgentRuntime`, `ModelGateway`, `ChatMemoryStore` injectable).
- [x] **5.4** Gate green (full suite). Commit: `refactor(telegram): drive rendering from AgentEvent stream via AgentRuntime`

## Task 6: Retire `Assistant` — dedicated §8 deletion commit

- [x] **6.1** Author `docs/adr/0007-agent-runtime-owns-conversation-memory.md`: memory ownership moves to `AgentRuntime` + `ChatMemoryStore` SPI; `@RegisterAiService` retired; citations (quarkus-langchain4j Messages-and-Memory + Task 3 doc check); ADR 0006 lineage (app-scope + explicit-session-id invariant carries forward; its `@PreDestroy` failure mode is structurally gone — no AI service exists); failure-persistence deviation.
- [x] **6.2** Delete `Assistant.java`, `RecordingChatModel.java` (both unreferenced since Task 5); remove `quarkus.langchain4j.chat-memory.memory-window.max-messages` (inert without an AI service). The pre-delete-guard hook fires its advisory §8 warning — expected and satisfied by this task's ADR + doc citations.
- [x] **6.3** Gate green — the Task 5 memory guards prove cross-request persistence survived the deletion. Commit: `refactor!: retire @RegisterAiService Assistant — runtime owns memory (ADR 0007)`

## Task 7: Package move → `adapter.telegram`

- [x] **7.1** `git mv` `com.quark.telegram` → `com.quark.adapter.telegram` (main + test trees); move `RecordingStreamingChatModel` → `com.quark.provider.gemini` (test tree). Package/import lines only; CI needs no edit (it runs the whole suite).
- [x] **7.2** Gate green. Commit: `refactor: move telegram adapter to com.quark.adapter.telegram`

## Task 8: Docs close-out

- [x] **8.1** `ARCHITECTURE.md`: Bridge table marks Plans 1–4 landed; short current-state note in the Destination section. `README.md`: what-runs-today + deferred list. `CLAUDE.md` §1: layered package layout replaces the flat-layout sentence.
- [x] **8.2** `docs/progress.md`: trajectory entry — gate counts, and verification honesty: integration tests at the request-context boundary, **not** a live Telegram smoke.
- [x] **8.3** `./gradlew spotlessApply test` → green. Commit: `docs: architecture/README/CLAUDE.md reflect plan 4 runtime extraction`

---

## Explicitly OUT of scope

| Deferred | Owner plan |
|----------|------------|
| `ProviderPreferenceStore`, NIM gateway, `/provider`, `/status` | Plan 5 |
| REST + SSE adapter, `GET /history/{sessionId}` | Plan 6 |
| ArchUnit rules, Micrometer metrics, MDC propagation | Plan 7 |
| Tool/planning/retrieval event variants; Redis-backed store | future |

## Done criteria

- `./gradlew test` zero failures/errors (CLAUDE.md §3); final suite ≈ 45+ tests (30 preserved-behavior + new store/gateway/runtime units).
- `TelegramConversationMemoryTest` green with unchanged assertions at unchanged altitude (request context per call) — the §8 backstop survives the extraction.
- `core` has zero framework imports; adapter imports nothing from `provider.*` or langchain4j (spot-check now; ArchUnit enforces in Plan 7).
- ADR 0007 committed; `Assistant` deletion isolated in its own commit after the cutover commit proved the suite green without it.
- Plan 5–7 items **not** implemented (WIP = 1, ADR 0003 scope).

## Rollback

Every task is one green commit — `git revert` any independently. The only destructive step (Task 6) is isolated after the cutover commit. Branch-level rollback: don't merge the PR.

## Self-Review

- [x] Event contract matches ADR 0001 exactly (7 variants, turnId, one terminal, failures-as-events, `instanceof` renderers).
- [x] Boundary table (ADR 0002) satisfied by import inspection for every new/moved file.
- [x] No behavioral assertion weakened or deleted in adapted tests.
- [x] ADR 0006 revisit trigger ("Introducing the Plan 4 ChatMemoryStore — pair it with this scope") honored: store is `@ApplicationScoped`.
