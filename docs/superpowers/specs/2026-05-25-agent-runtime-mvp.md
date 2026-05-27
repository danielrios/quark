# Agent Runtime — MVP Design

**Status**: design, awaiting implementation plan
**Date**: 2026-05-26
**Project**: `quark` (Quarkus 3.35.4, Java 25)

---

# 1. Goal

Build the smallest possible working agent runtime that proves the core loop:

1. receive a message,
2. call an LLM,
3. stream the response,
4. keep short conversation memory,
5. expose it through Telegram and HTTP.

This is **not** the final architecture.

The MVP intentionally prioritizes:

* fast iteration,
* low file count,
* minimal abstractions,
* working end-to-end flow.

The event-driven runtime, provider abstractions, ArchUnit boundaries, and multi-adapter architecture are deferred until the system proves useful.

---

# 2. MVP Scope

## In scope

* Single Quarkus application.
* Telegram bot via long polling.
* Simple REST endpoint.
* Gemini integration only.
* Streaming responses.
* In-memory chat history.
* `/reset` command.
* Basic logging.
* Minimal tests.

## Out of scope

Deferred completely:

* `AgentRuntime`
* `AgentEvent`
* `ModelGateway`
* provider abstraction
* NIM support
* SSE
* ArchUnit
* observability pipeline
* reflection/planning/tools
* provider preference store
* webhook mode
* Redis/Postgres
* complex package boundaries
* retry policies
* Micrometer metrics

---

# 3. Architecture Philosophy

The MVP follows a strict **walking skeleton** approach:

> working software first, architecture second.

The system should stay:

* understandable in one sitting,
* debuggable without diagrams,
* easy to rewrite.

If an abstraction has only one implementation, it probably should not exist yet.

---

# 4. Package Layout

```text
com.quark
├── chat/
│   ├── ChatService.java
│   ├── ChatMemory.java
│   ├── ChatMessage.java
│   └── GeminiChatClient.java
│
├── telegram/
│   ├── TelegramBotRunner.java
│   ├── TelegramMessageHandler.java
│   └── TelegramRenderer.java
│
├── rest/
│   └── ChatResource.java
│
├── config/
│   └── AppConfig.java
│
└── shared/
    └── Provider.java
```

No `core/`, `runtime/`, `adapter/`, `provider/` split yet.

Those appear later when there is enough real code to justify them.

---

# 5. Runtime Flow

## REST

```text
POST /chat
   ↓
ChatService.chat(sessionId, message)
   ↓
load memory
   ↓
call Gemini streaming API
   ↓
accumulate tokens
   ↓
persist assistant response
   ↓
return full response
```

## Telegram

```text
Telegram update
   ↓
TelegramMessageHandler
   ↓
ChatService.stream(...)
   ↓
TelegramRenderer edits message live
```

One service.
One provider.
One memory implementation.

---

# 6. Core Components

## 6.1 ChatService

Main orchestration class.

Responsibilities:

* load chat history,
* append user message,
* call Gemini,
* stream tokens,
* persist assistant response.

Proposed shape:

```java
Multi<String> stream(String sessionId, String message)

Uni<String> chat(String sessionId, String message)
```

No event system yet.

Streaming plain text is enough for the MVP.

---

## 6.2 ChatMemory

Simple in-memory bounded conversation store.

Suggested structure:

```java
Map<String, List<ChatMessage>>
```

Key:

* `sessionId`

Bound:

* configurable max messages.

No provider isolation yet.

No SPI yet.

No persistence yet.

---

## 6.3 GeminiChatClient

Thin wrapper around LangChain4j.

Responsibilities:

* isolate Gemini-specific configuration,
* expose streaming text generation.

Possible shape:

```java
Multi<String> stream(List<ChatMessage> history)
```

No provider abstraction.

No gateway layer.

No model events.

---

## 6.4 TelegramRenderer

Stateful streaming renderer.

Responsibilities:

* send placeholder message,
* throttle edits,
* split long messages,
* final flush.

This is the only genuinely complex part of the MVP because Telegram UX depends on it.

---

# 7. HTTP API

## POST `/chat`

Request:

```json
{
  "sessionId": "abc",
  "message": "hello"
}
```

Response:

```json
{
  "reply": "Hello!"
}
```

---

## POST `/chat/stream`

SSE streaming endpoint.

Minimal format:

```text
data: Hel

data: lo

data: !
```

No typed events yet.

No `turnStarted`.

No `turnCompleted`.

Just token streaming.

---

## GET `/history/{sessionId}`

Debug endpoint.

Returns current in-memory conversation.

Useful during development and tests.

---

# 8. Telegram Commands

| Command   | Behaviour            |
| --------- | -------------------- |
| `/start`  | welcome message      |
| `/reset`  | clears memory        |
| `/status` | uptime + memory size |

No `/provider` command yet.

Only Gemini exists.

---

# 9. Streaming Rules

## Telegram

Flow:

```text
sendMessage("...")
↓
receive tokens
↓
accumulate buffer
↓
editMessageText()
↓
final flush
```

Constraints:

* throttle edits (~750ms),
* split around 3900 chars,
* continue on Telegram edit failures.

Plain text only.

No markdown.

---

# 10. Error Handling

Keep it simple.

## Runtime failures

Map errors into user-friendly messages:

| Type    | User message                      |
| ------- | --------------------------------- |
| timeout | "Provider timeout."               |
| auth    | "Provider authentication failed." |
| generic | "Something went wrong."           |

No formal error taxonomy yet.

No retry system.

No structured event failures.

---

# 11. Observability

Minimal:

* structured logs,
* request correlation id,
* provider latency log.

No Micrometer yet.

No event metrics.

No distributed tracing.

---

# 12. Testing Strategy

## Unit tests

Focus only on:

* memory behavior,
* Telegram rendering,
* chat orchestration.

## Integration tests

Optional and gated by env vars:

* Gemini API smoke test,
* Telegram smoke test.

No ArchUnit yet.

No heavy contract testing yet.

---

# 13. Deferred Architecture

These are intentionally postponed until the MVP proves itself.

## Planned later

* `AgentRuntime`
* `AgentEvent`
* provider abstraction
* multiple providers
* event-driven runtime
* SSE typed events
* provider preferences
* ArchUnit boundaries
* Redis/Postgres memory
* tools/planning/reflection
* observability pipeline
* retry policies

The target architecture already exists in:

* ADRs,
* `ARCHITECTURE.md`,
* future plans.

The MVP is only the shortest path to validating:

> “does the runtime actually feel good to use?”
