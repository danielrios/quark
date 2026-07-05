# 0007 — `AgentRuntime` owns conversation memory; the `@RegisterAiService` `Assistant` is retired

**Status:** Accepted, 2026-07-04.
**Deciders:** Daniel + Plan 4 implementation session.
**Supersedes (in part):** [ADR 0006](0006-application-scoped-ai-service-for-memory.md) — the mechanism it governed is deleted; the invariant it established carries forward here.

**Related documents:**
- [`src/main/java/com/quark/runtime/AgentRuntime.java`](../../src/main/java/com/quark/runtime/AgentRuntime.java) — the new memory owner.
- [`src/main/java/com/quark/memory/InMemoryChatMemoryStore.java`](../../src/main/java/com/quark/memory/InMemoryChatMemoryStore.java) — the Plan 4 `ChatMemoryStore` seam ADR 0003 reserved.
- [`src/test/java/com/quark/adapter/telegram/TelegramConversationMemoryTest.java`](../../src/test/java/com/quark/adapter/telegram/TelegramConversationMemoryTest.java) — the regression guard, altitude unchanged.
- quarkus-langchain4j docs: [Models](https://docs.quarkiverse.io/quarkus-langchain4j/dev/models.html) — model beans are built from `application.properties` config and directly `@Inject`-able; no `@RegisterAiService` required.
- quarkus-langchain4j docs: [Messages and Memory](https://docs.quarkiverse.io/quarkus-langchain4j/dev/messages-and-memory.html) — the AI-service memory machinery this ADR retires.
- [Plan 4](../superpowers/plans/2026-07-03-plan-4-runtime-extraction.md) — the extraction this decision lands in.

---

## Context

Plan 4 (the refactor phase ADR 0003 scheduled) extracted the destination seams from
working code: `AgentRuntime` orchestrates a turn as a `Multi<AgentEvent>` (ADR 0001),
`com.quark.memory.ChatMemoryStore` is the conversation-memory SPI, and `ModelGateway`
isolates langchain4j inside `provider.gemini` (ADR 0002).

With those seams in place, the runtime loads history and persists turns **explicitly** —
`ModelGateway.stream(List<ChatMessage>)` receives the fully built prompt. Nothing needs
langchain4j's implicit `@MemoryId` memory management anymore, which makes the
`@RegisterAiService Assistant` interface redundant: its two jobs (model access, memory)
are now owned by `GeminiModelGateway` and `AgentRuntime` respectively.

Deleting `Assistant` is a lifecycle-bearing deletion under CLAUDE.md §8 (it is the bean
ADR 0006 governs). Per §8, the documented contract was checked **before** deletion:

- **Model access without an AI service** — the quarkiverse *Models* page documents direct
  injection (`@Inject ChatModel` / streaming variants), with beans built from
  `quarkus.langchain4j.*` config at startup. Verified empirically by
  `RuntimeWiringTest` and `GeminiModelGatewayTest` against the `%test` config.
- **What the AI service's memory machinery did** — the *Messages and Memory* page plus the
  ADR 0006 investigation: the generated bean's `@PreDestroy` chain
  (`QuarkusAiServiceContext.close → ChatMemoryService.clearAll → deleteMessages`) wipes
  memory when the service's scope ends. That failure mode was scope-managed in ADR 0006;
  retiring the AI service removes the machinery **structurally** — there is no generated
  bean left whose lifecycle can clear the store.

## Decision

1. **`AgentRuntime` owns conversation memory.** It loads history via the quark
   `ChatMemoryStore` SPI before invoking the gateway and appends the `USER` and
   `ASSISTANT` messages after a successful turn. Adapters never touch the store
   (ADR 0002); `/reset` goes through `AgentRuntime.reset(sessionId)`.

2. **`Assistant.java` is deleted**, along with the now-unreferenced blocking test fake
   `RecordingChatModel.java`. The `com.quark.chat` main package ceases to exist.

3. **`quarkus.langchain4j.chat-memory.memory-window.max-messages` is removed** from
   `application.properties` — it configured the AI-service memory window and is inert
   without one. The bound lives on as `quark.memory.max-messages=20` (Plan 2's window
   size, enforced by `InMemoryChatMemoryStore` eviction).

4. **The ADR 0006 invariant carries forward, restated for the SPI era:**
   - the store must outlive per-update request contexts → `InMemoryChatMemoryStore` is
     `@ApplicationScoped`;
   - sessions are isolated only by an explicit id per call → every `TurnRequest` carries
     `sessionId`; there is no ambient/default session.
   `TelegramConversationMemoryTest` remains the CI backstop at its original altitude
   (one activated-then-terminated CDI request context per update), now guarding the path
   `dispatch → AgentRuntime → GeminiModelGateway → StreamingChatModel`.

## Deliberate behavior deviations

- **Nothing is persisted on a failed turn.** langchain4j's AiServices appended the user
  message before invoking the model, so a failed turn still left it in history. The
  runtime persists user + assistant together only after stream completion. A failed or
  cancelled turn leaves memory untouched — retrying a failed message does not double it.
  (Caveat recorded in the runtime javadoc: the two appends are not atomic if a store
  implementation throws mid-pair.)
- **Window+1 prompt at saturation.** The store bounds *persisted* history at
  `max-messages`; the in-flight prompt adds the pending user message on top. Plan 2's
  `MessageWindowChatMemory` counted the pending message inside the window. The
  one-message difference is accepted and documented in the runtime javadoc.
- **Blank completions persist nothing.** A zero-token model completion still emits
  `ModelCompleted` + `TurnCompleted("")`, but neither the user nor the (empty)
  assistant message is persisted: renderers surface the error fallback for such
  turns, so memory agrees the turn did not happen — a resend does not double the
  user message, and no empty assistant message is replayed into later prompts.
  (langchain4j's AiServices persisted empty replies.)
- **`TurnFailed.reason` is message-only.** Exception class names never enter the
  event stream (Plan 6 serializes it to external SSE clients); full stack traces
  stay in the log, correlated by `turnId`.

## Consequences

- langchain4j now appears **only** in `provider.gemini` (main tree) — the ADR 0002
  boundary is real and becomes ArchUnit-enforceable in Plan 7.
- Memory remains in-process and restart-lossy (unchanged from ADR 0006); the Redis-class
  store now has a first-party SPI to implement.
- Reintroducing any `@RegisterAiService` bean re-opens ADR 0006's scope trap — read it
  first (its Context section stays authoritative for that machinery).

## Revisit triggers

- A persistent/shared `ChatMemoryStore` implementation (multi-instance deployment).
- Concurrent adapters (Plan 6 REST/SSE): same-session `load`-during-`append` snapshot
  race documented in `InMemoryChatMemoryStore`.
- Any desire to persist user messages on failed turns (product decision, not a bug).
