# Plan 3 — Telegram Streaming via Throttled Message Edits

**Status**: approved, awaiting implementation plan
**Date**: 2026-06-25
**Project**: `quark` (Quarkus 3.35.4, Java 25)

---

# 1. Goal

Replace the current wait-then-send pattern with token-by-token streaming that edits a Telegram placeholder message as the model generates. Users see the response building live instead of waiting for the full reply.

This is Plan 3 per ADR 0003. No new abstractions are introduced (no interfaces, no SPIs).

---

# 2. Scope

## In scope

* `Multi<String> streamChat()` on `Assistant` alongside the existing blocking `chat()`
* `TelegramStreamHandler` — new plain `@ApplicationScoped` bean owning the streaming loop
* `TelegramApi.editMessageText()` and `sendMessage` updated to return `message_id`
* New DTOs in `TelegramMessages`: `SendMessageResponse`, `MessageResult`, `EditMessageText`
* Throttle interval configurable via `@ConfigProperty` (default 750ms)
* 4096-char buffer clamping via existing `clampToTelegramLimit()`

## Out of scope

* Multi-message chunking for responses > 4096 chars (deferred; see existing comment in `TelegramMessages`)
* Partial-buffer preservation on `onError` (future UX polish)
* Markdown / MarkdownV2 rendering
* SSE / REST streaming endpoint (Plan 6)

---

# 3. Components

| File | Change |
|------|--------|
| `Assistant` | Add `Multi<String> streamChat(@MemoryId String sessionId, @UserMessage String userMessage)` alongside existing `String chat()` |
| `TelegramApi` | `sendMessage` return type `void` → `SendMessageResponse`; add `editMessageText(EditMessageText)` |
| `TelegramMessages` | Add `SendMessageResponse`, `MessageResult`, `EditMessageText` records |
| `TelegramStreamHandler` | **New** `@ApplicationScoped` bean — owns streaming loop |
| `TelegramBotRunner` | `chat()` branch delegates to `streamHandler.stream(...)` instead of `assistant.chat()` |

---

# 4. Data Flow

```
handle(update)  [CDI request context active]
  ↓
TelegramApi.sendMessage(chatId, "…")
  → SendMessageResponse { messageId }
  ↓
TelegramStreamHandler.stream(chatId, messageId, sessionId, userText)
  ↓
  CountDownLatch latch = new CountDownLatch(1)
  StringBuilder buffer = new StringBuilder()          ← method-local
  long[] lastEdit = { clock.millis() }                ← method-local (array for lambda mutability)

  assistant.streamChat(sessionId, userText)   ← returns Multi<String>
    .subscribe().with(
        token -> {
            buffer.append(token)
            long now = clock.millis()
            if (now - lastEdit[0] >= throttleMs):
                try { api.editMessageText(chatId, messageId, clamp(buffer)) }
                catch (Exception e) { Log.warn(...) }   ← swallow, continue
                lastEdit[0] = now
        },
        error -> {
            Log.error(...)
            try { api.editMessageText(chatId, messageId, "Something went wrong.") }
            catch (Exception ignored) {}
            latch.countDown()
        },
        () -> {
            try { api.editMessageText(chatId, messageId, clamp(buffer)) }  ← final flush
            catch (Exception e) { Log.warn(...) }
            latch.countDown()
        }
    )
  latch.await()
  ↓
[handle() finally block terminates CDI request context]
```

**Key design points:**

- `buffer` and `lastEdit` are **method-local** — `TelegramStreamHandler` is a singleton; instance fields would corrupt concurrent calls.
- `latch.await()` blocks the virtual thread, keeping the CDI request context alive for the full stream duration. The `finally { requestContext.terminate() }` in `TelegramBotRunner.handle()` only fires after `stream()` returns.
- Edit failures inside the `token` callback are caught and swallowed — this also silently recovers from Telegram's `400 Bad Request` on unclosed markdown tags mid-stream.
- `onComplete` always runs regardless of mid-stream edit failures, guaranteeing a final flush.

---

# 5. API Changes

### `Assistant`

```java
@RegisterAiService
@ApplicationScoped
public interface Assistant {

    @SystemMessage("...")
    String chat(@MemoryId String sessionId, @UserMessage String userMessage);

    @SystemMessage("...")
    Multi<String> streamChat(@MemoryId String sessionId, @UserMessage String userMessage);
}
```

`Multi<String>` is the correct streaming return type for Quarkus LangChain4j `@RegisterAiService` methods (verified against official docs). `TokenStream` is only for the non-Quarkus `AiServices.create()` path.

### `TelegramApi`

```java
@POST @Path("/sendMessage")
SendMessageResponse sendMessage(SendMessage message);

@POST @Path("/editMessageText")
void editMessageText(EditMessageText edit);
```

### New records in `TelegramMessages`

```java
record SendMessageResponse(boolean ok, MessageResult result) {}
record MessageResult(@JsonProperty("message_id") long messageId) {}
record EditMessageText(
    @JsonProperty("chat_id") long chatId,
    @JsonProperty("message_id") long messageId,
    String text
) {}
```

Telegram's `sendMessage` response includes many fields beyond `ok` and `result.message_id`. Quarkus's default Jackson configuration ignores unknown properties, so no additional annotation is needed.

---

# 6. Error Handling

| Scenario | Handling |
|----------|----------|
| `editMessageText` fails mid-stream (400 same-text, 429 rate limit, unclosed markdown) | Log warn, swallow, continue — next tick or `onComplete` catches up |
| Buffer exceeds 4096 chars | Clamp with `clampToTelegramLimit()` before every edit call |
| `streamChat()` emits error (auth, timeout, network) | `error` callback → edit message to `"Something went wrong."`, count down latch |
| `sendMessage("…")` fails (bot blocked, removed from group) | Existing `handle()` catch block logs it, update dropped silently |

Future polish: on error, preserve partial buffer as `buffer + "\n\n[Error: Stream interrupted]"` instead of replacing entirely. Deferred.

---

# 7. Configuration

```properties
# application.properties
quark.telegram.stream-throttle-ms=750

# test profile (application.properties or @QuarkusTestProfile)
%test.quark.telegram.stream-throttle-ms=0
```

Default 750ms keeps edits safely below Telegram's ~1 edit/sec per-chat rate limit. Setting to `0` in tests makes every token trigger an edit call, exercising the wiring without timing dependencies.

---

# 8. Testing

`TelegramStreamHandler` is tested with a fake `TelegramApi` and the `Assistant` interface mocked via `QuarkusMock.installMockForType(Assistant.class, ...)` returning `Multi.createFrom().items("token1", "token2", ...)`. This follows the pattern established in `TelegramConversationMemoryTest`. **Do not** extend `RecordingChatModel` — it implements `ChatModel` (blocking), not the streaming path.

Throttle timing uses an injected `java.time.Clock` bean to avoid `Thread.sleep` flakiness.

| # | Test | Assert |
|---|------|--------|
| 1 | Tokens within throttle window are batched | Clock advances < throttleMs between tokens; `editMessageText` not called until window elapses |
| 2 | Final flush always runs | Emit tokens, trigger completion; `editMessageText` called with complete buffer even when last tokens did not hit the threshold |
| 3 | Mid-stream edit failure is swallowed | First `editMessageText` call throws; stream does not crash; completion fires and final edit succeeds with full accumulated buffer |
| 4 | `onError` replaces message | `streamChat()` emits error; `editMessageText` called with `"Something went wrong."` |
| 5 | Buffer clamped at 4096 | Token pushes buffer past 4096 chars; `editMessageText` receives string of length ≤ 4096 |

---

# 9. Deferred

* Multi-message splitting when response exceeds 4096 chars
* Partial-buffer preservation on stream error
* Markdown / MarkdownV2 rendering
* REST/SSE streaming endpoint (Plan 6)
