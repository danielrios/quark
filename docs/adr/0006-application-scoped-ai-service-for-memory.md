# 0006 — `@ApplicationScoped` AI service for cross-request conversation memory

**Status:** Superseded in part by [ADR 0007](0007-agent-runtime-owns-conversation-memory.md), 2026-07-04 — the `@RegisterAiService` bean this ADR governs was retired in Plan 4; the invariant it established (application-scoped memory + explicit per-call session id) carries forward there. The Context section stays authoritative for the AI-service scope trap. Originally accepted 2026-05-30.
**Deciders:** Daniel + debugging session (PR #14 review).

**Related documents:**
- `src/main/java/com/quark/chat/Assistant.java` — the bean this ADR governed; deleted in Plan 4 (ADR 0007).
- [`src/test/java/com/quark/adapter/telegram/TelegramConversationMemoryTest.java`](../../src/test/java/com/quark/adapter/telegram/TelegramConversationMemoryTest.java) — the regression guard.
- quarkus-langchain4j docs: [Messages and Memory](https://docs.quarkiverse.io/quarkus-langchain4j/dev/messages-and-memory.html).
- [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md) — reserves the *custom* `ChatMemoryStore` seam for Plan 4.

---

## Context

Plan 2 added per-session conversation memory via `@MemoryId` on `Assistant.chat()`,
relying on quarkus-langchain4j's built-in `MessageWindowChatMemory` over the default
in-memory `ChatMemoryStore`.

During PR #14 review, a custom `AppScopedChatMemoryStore` (`@ApplicationScoped`,
added alongside `@ApplicationScoped` on the `Assistant`) was challenged as redundant,
since the default store is a `@Singleton` and therefore already persists across requests.
A store-level test confirmed the default store persists — so the custom store was
removed **and** the `Assistant` was reverted to its default scope. Live testing then
showed conversation memory was **broken**: every turn started blank.

Root cause, confirmed in the extension bytecode (`quarkus-langchain4j-core:1.9.2`) and
the official docs:

```
@RegisterAiService defaults to @RequestScoped.
TelegramBotRunner.handle() activates a fresh CDI request context per Telegram update and
terminates it in a finally block. On termination the generated AI-service bean's @PreDestroy
runs:
    QuarkusAiServiceContext.close()
      → ChatMemoryService.clearAll()
        → ChatMemory.clear()   (for every cached memory, including explicit @MemoryId ones)
          → ChatMemoryStore.deleteMessages(sessionId)
```

So the session's history is **deleted from the store at the end of every update**. This is
independent of the store implementation — a custom or Redis-backed store is wiped just the
same. The store-level test passed because it never exercised the AI-service lifecycle.

The decisive evidence is an end-to-end test (fake `ChatModel` via
`QuarkusMock.installMockForType`, two turns across separate request contexts), run as a
one-variable matrix:

| Config | `Assistant` scope | Store | Memory survives? |
|--------|-------------------|-------|------------------|
| A | `@ApplicationScoped` | custom `AppScopedChatMemoryStore` | yes |
| B | `@ApplicationScoped` | **default `@Singleton`** | **yes** |
| D | **`@RequestScoped`** (default) | default | **no** |

B vs D isolates the scope; A vs B isolates the store. The scope is load-bearing; the custom
store is not.

## Decision

1. `Assistant` is annotated **`@ApplicationScoped`**, overriding the `@RegisterAiService`
   default of `@RequestScoped`. This keeps the service — and its `ChatMemoryService` cache —
   alive across Telegram updates, so the per-request `@PreDestroy` cleanup never fires
   between turns. Sessions remain isolated because every call carries an explicit
   `@MemoryId` (the chat id). This is the pattern the quarkus-langchain4j docs prescribe for
   shared-instance memory.

2. The **default in-memory `ChatMemoryStore` is used; no custom store class.** The removed
   `AppScopedChatMemoryStore` was a duplicate of the extension's own `InMemoryChatMemoryStore`
   and did nothing the scope fix doesn't already cover. This also keeps the custom
   `ChatMemoryStore` seam unimplemented, as ADR 0003 reserves it for Plan 4.

3. A regression test (`TelegramConversationMemoryTest`) drives the real
   `dispatch → Assistant.chat → memory` path across separate request contexts and asserts
   turn 2 replays turn 1. Removing the scope makes it fail. The store-level
   `ChatMemoryPersistenceTest` was deleted: it passed even when end-to-end memory was broken,
   so it gave false confidence.

## Consequences

- Memory is **in-process only** — lost on restart. Acceptable for the MVP (single instance).
  Cross-restart / shared persistence (a real `ChatMemoryStore`, e.g. Redis) arrives with the
  Plan 4 seam, and at that point the store must **also** be paired with this non-request scope
  to survive across requests.
- `@ApplicationScoped` on an AI service is only safe **because** every call supplies an
  explicit `@MemoryId`. If a future method omits `@MemoryId`, all callers would share one
  memory. Any new `Assistant` method must take a `@MemoryId`.
- The service is now a **single shared instance** across all updates, and its
  `ChatMemoryService` `chatMemories` map accumulates one entry per session, evicted only by
  `/reset` or restart (no per-session TTL). This is safe today **only because the Telegram
  poller processes updates sequentially on one virtual thread** — there is no concurrent
  multi-session access to worry about yet. Concurrency (the Plan 6 REST adapter, or a
  parallelised poller) revisits both the sharing assumption and unbounded session growth.

## Revisit triggers

- Introducing the Plan 4 `ChatMemoryStore` abstraction (pair it with this scope).
- Adding a second instance / horizontal scaling (in-process memory no longer suffices).
- Adding an `Assistant` method without a `@MemoryId`.
