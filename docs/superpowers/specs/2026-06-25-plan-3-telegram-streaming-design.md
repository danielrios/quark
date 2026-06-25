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

* `TokenStream streamChat()` on `Assistant` alongside the existing blocking `chat()`
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
| `Assistant` | Add `TokenStream streamChat(@MemoryId String sessionId, @UserMessage String userMessage)` alongside existing `String chat()` |
| `TelegramApi` | `sendMessage` return type `void` → `SendMessageResponse`; add `editMessageText(EditMessageText)` |
| `TelegramMessages` | Add `SendMessageResponse`, `MessageResult`, `EditMessageText` records |
| `TelegramStreamHandler` | **New** `@ApplicationScoped` bean — owns streaming loop |
| `TelegramBotRunner` | `chat()` branch delegates to `streamHandler.stream(...)` instead of `assistant.chat()` |

---

# 4. Data Flow

```
handle(update)
  ↓
TelegramApi.sendMessage(chatId, "…")
  → SendMessageResponse { messageId }
  ↓
TelegramStreamHandler.stream(chatId, messageId, sessionId, userText)
  ↓
  assistant.streamChat(sessionId, userText)   ← TokenStream
    .onNext(token) →
        buffer.append(token)
        if (clock.millis() - lastEdit >= throttleMs):
            api.editMessageText(chatId, messageId, clamp(buffer))
            lastEdit = clock.millis()
    .onComplete(response) →
        api.editMessageText(chatId, messageId, clamp(buffer))   ← final flush
    .onError(err) →
        Log.error(...)
        api.editMessageText(chatId, messageId, "Something went wrong.")
    .start()                ← blocks virtual thread until stream finishes
```

Edit failures inside `onNext` are caught, logged, and swallowed — the buffer keeps accumulating and `onComplete` always flushes the final state. This also silently recovers from Telegram's `400 Bad Request` on unclosed markdown tags mid-stream.

---

# 5. API Changes

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

---

# 6. Error Handling

| Scenario | Handling |
|----------|----------|
| `editMessageText` fails mid-stream (400 same-text, 429 rate limit, unclosed markdown tag) | Log, swallow, continue — next tick or `onComplete` catches up |
| Buffer exceeds 4096 chars | Clamp with `clampToTelegramLimit()` before every edit call |
| `streamChat()` throws (auth, timeout, network) | `onError` fires → edit message to `"Something went wrong."` |
| `sendMessage("…")` fails (bot blocked, removed from group) | Existing `handle()` catch block logs it, update dropped silently |

Future polish: on `onError`, preserve partial buffer as `buffer + "\n\n[Error: Stream interrupted]"` instead of replacing entirely. Deferred.

---

# 7. Configuration

```properties
# application.properties
quark.telegram.stream-throttle-ms=750
```

Default: 750ms — keeps edits safely below Telegram's ~1 msg/sec per-chat rate limit while delivering a snappy UX. Set to `0` in tests to make every token trigger an edit.

---

# 8. Testing

`TelegramStreamHandler` is tested with a fake `TelegramApi` and `RecordingChatModel` extended to emit `TokenStream`. Throttle timing uses an injected `java.time.Clock` to avoid `Thread.sleep` flakiness.

| # | Test | Assert |
|---|------|--------|
| 1 | Tokens arrive within throttle window | With `Clock` advanced +750ms per token, `editMessageText` called once per throttle window; tokens between windows batched into one edit |
| 2 | Final flush always runs | Emit tokens, trigger `onComplete`; `editMessageText` called with complete buffer even when last tokens did not hit the threshold |
| 3 | Mid-stream edit failure is swallowed | First `editMessageText` call throws; stream does not crash; `onComplete` fires and final edit succeeds with full accumulated buffer |
| 4 | `onError` replaces message | `streamChat()` throws; `editMessageText` called with `"Something went wrong."` |
| 5 | Buffer clamped at 4096 | Token pushes buffer past 4096 chars; `editMessageText` receives string of length ≤ 4096 |

---

# 9. Deferred

* Multi-message splitting when response exceeds 4096 chars
* Partial-buffer preservation on stream error (`onError` keeps partial answer)
* Markdown / MarkdownV2 rendering
* REST/SSE streaming endpoint (Plan 6)
