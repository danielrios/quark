# Agent Runtime — Slice 1 Design

**Status**: design, awaiting implementation plan
**Date**: 2026-05-25
**Project**: `quark` (Quarkus 3.35.4, Java 25, single Gradle module)
**Supersedes**: the truncated `2026-05-25-hermes-inspired-agent-design.md` in this directory.

## 1. Goal

Build a clean, observable, **streaming-first execution runtime** for an agent platform. Slice 1 is not a full autonomous agent — there are no tools, no planning, no autonomous loops. What slice 1 delivers is the *runtime shape* that later slices extend additively: an event-driven core that loads memory, calls a model gateway, emits a typed event stream, and is consumed by transport renderers (REST/SSE and Telegram).

The discriminating success condition: adding tools, planning, episodic memory, or new providers in later slices must be additive — new event variants, new pipeline stages, new gateway impls, new memory backends — without reshaping any existing component.

## 2. Scope

### In scope
- A single Quarkus service exposing chat over **REST + SSE** and over **Telegram** (long-polling).
- An `AgentRuntime` orchestration core that returns a `Multi<AgentEvent>` per turn.
- A `ModelGateway` provider abstraction with two implementations: **Google Gemini** and **NVIDIA NIM** (NIM via the OpenAI-compatible client).
- Working memory per `(sessionId, provider)` using langchain4j's `ChatMemoryStore` SPI, in-process bounded implementation.
- `ProviderPreferenceStore` SPI with an in-process implementation; per-session provider selection persists across requests within a JVM lifetime.
- Telegram update source as an interface with one impl (polling); webhook is *not* defined as a placeholder type — it gets added as a real impl when implemented.
- Stateful per-chat **Telegram stream renderer** producing throttled message edits, with atomic message-split handling at the 3900-character boundary. Plain text only in slice 1.
- ArchUnit-enforced package boundaries as build-time gate.
- Observability: structured logging with `turnId` correlation; Micrometer counters per `AgentEvent` variant; per-turn timer.
- Unit tests with fakes for runtime and renderer; ArchUnit tests; integration tests gated on env vars per external dependency.

### Out of scope (deferred to later slices)
- Tools, tool registry, function calling.
- Planner / executor decomposition.
- Episodic memory (Postgres, pgvector, embeddings) and procedural memory.
- Async reflection pipeline.
- Redis-backed working memory (the `ChatMemoryStore` SPI is the seam).
- Persistent or distributed `ProviderPreferenceStore`.
- Webhook `TelegramUpdateSource` (interface admits a second impl; one will be added when implemented).
- Markdown rendering in Telegram (plain text only).
- Authentication, multi-tenancy, multi-bot deployments.
- Telegram inline mode, photos, files, callback queries.
- Retry policy inside the runtime (one attempt, fail fast).
- Cancel-on-Telegram-chat-block.

### Success criteria
1. `curl POST /chat/stream` returns SSE events: `turnStarted` first, then `tokenEmitted` events, terminating in `turnCompleted` + `turnEnded`.
2. A follow-up turn on the same `sessionId` produces a reply that demonstrates the model saw prior turns.
3. Switching the `provider` field changes which backend handles the turn; memory is isolated per `(sessionId, provider)`.
4. From a real Telegram chat: sending a message produces a placeholder reply that updates live via edits and finalises with the full response.
5. `/provider`, `/reset`, `/status`, `/start` commands behave as specified.
6. `./gradlew test` passes with no API keys configured; integration tests are skipped via JUnit assumptions when their gating env vars are absent.
7. ArchUnit tests fail the build on any boundary violation.

## 3. Architecture

### 3.1 Module layout

```
com.quark
├── core/                   AgentEvent, Provider, ModelRequest/ModelResponse, TurnRequest,
│                           shared DTOs. No Quarkus, no langchain4j, no transport deps.
├── runtime/                AgentRuntime, TurnContext, event emission, MDC propagation.
├── memory/
│   ├── chat/               ChatMemoryStore SPI binding + InMemoryChatMemoryStore.
│   └── preference/         ProviderPreferenceStore SPI + InMemoryProviderPreferenceStore.
├── provider/               ModelGateway SPI + ModelEvent.
│   ├── gemini/             GeminiModelGateway (wraps a langchain4j @RegisterAiService impl).
│   └── nim/                NimModelGateway     (wraps a langchain4j @RegisterAiService impl
│                           configured against the OpenAI extension with NIM base URL).
├── adapter/
│   ├── rest/               ChatResource + SseEventRenderer.
│   └── telegram/           TelegramUpdateSource, PollingTelegramUpdateSource,
│                           TelegramMessageHandler, TelegramStreamRenderer,
│                           ProviderCommand handlers, TelegramClient binding.
└── config/                 Config beans, env binding, provider wiring.
```

Test sources include an `archtest` package containing the ArchUnit boundary tests.

Single Quarkus module. Package boundaries — not Gradle subprojects — are the enforcement mechanism, validated by ArchUnit tests.

### 3.2 Pipeline

```
IncomingMessage (REST body | Telegram update)
   │
   ▼
Adapter ─ builds TurnRequest(sessionId, provider?, message)
   │
   ▼
AgentRuntime.execute(TurnRequest) ──────────────► Multi<AgentEvent>
   │
   ├─ load history       ChatMemoryStore.get((sessionId, provider))
   ├─ resolve provider   ProviderPreferenceStore (if provider unset on request)
   ├─ assemble request   system prompt + history + user message → ModelRequest
   ├─ invoke gateway     ModelGateway.stream(request) → Multi<ModelEvent>
   ├─ map + accumulate   ModelEvent → AgentEvent; collect tokens for memory persist
   ├─ on completion      append (user, assistant) turn to ChatMemoryStore
   └─ emit terminal      TurnCompleted or TurnFailed
                              │
                              ▼
                  Transport Renderer (one per adapter)
                  ├─ SseEventRenderer       Multi<AgentEvent> → SSE events
                  └─ TelegramStreamRenderer Multi<AgentEvent> → throttled message edits
```

Future pipeline stages — tool calls, planning, reflection — insert between "load history" and "invoke gateway" or after "map + accumulate". They emit additional `AgentEvent` variants. Existing renderers ignore unknown variants.

`TurnStarted` is emitted **synchronously on subscription**, before any I/O (memory load, provider resolution). This guarantees adapters see `turnId` immediately on subscribe, which the SSE adapter relies on to fulfil its "first event is always `turnStarted`" contract.

### 3.3 Core contracts

**`AgentEvent`** — sealed interface in `core`. Slice 1 variants:

| Variant | Payload |
|---|---|
| `TurnStarted` | `turnId`, `sessionId`, `provider`, `instant` |
| `MemoryLoaded` | `turnId`, `messageCount` |
| `ModelInvoked` | `turnId`, `provider`, `modelName` |
| `TokenEmitted` | `turnId`, `text` |
| `ModelCompleted` | `turnId`, `totalTokens`, `finishReason` |
| `TurnCompleted` | `turnId`, `durationMs` |
| `TurnFailed` | `turnId`, `errorClass`, `message`, `retryable` |

All carry `turnId` for correlation. The runtime emits exactly one of `{TurnCompleted, TurnFailed}` as the terminal event, then the `Multi` completes normally.

**`AgentRuntime`** — `Multi<AgentEvent> execute(TurnRequest)`. Sole orchestration entry point. Adapters never touch `ModelGateway`, `ChatMemoryStore`, or `ProviderPreferenceStore` directly.

**`ModelGateway`** — `Multi<ModelEvent> stream(ModelRequest)`. `ModelEvent` is the low-level provider stream shape (token, completion, error). One implementation per provider. The langchain4j `@RegisterAiService` interfaces live *inside* each gateway implementation; they are not exposed outside the `provider.<name>` package.

**`ChatMemoryStore`** — langchain4j's existing SPI. Slice 1 impl: in-process bounded store keyed by `(sessionId, provider)`. Bound configured via `quark.memory.chat.max-messages`.

**`ProviderPreferenceStore`** — `Provider get(String sessionId)`, `void set(String sessionId, Provider)`. Slice 1 impl: in-process map. Returns the configured default when no preference exists.

**`TelegramUpdateSource`** — plain interface. Methods: `void start()`, `void stop()`, `Multi<TelegramUpdate> updates()`. Single implementation in slice 1: `PollingTelegramUpdateSource`.

**`TelegramStreamRenderer`** — stateful, per-turn lifecycle. Owns: active `messageId`, per-message text buffer, `lastEditAt` timestamp, throttle interval. Consumes `Multi<AgentEvent>`, calls into a thin `TelegramClient` abstraction (sendMessage, editMessageText).

### 3.4 Package boundaries (enforced by ArchUnit)

| Package | May depend on | May not depend on |
|---|---|---|
| `core` | (nothing project-internal) | Quarkus, langchain4j, Telegram, Jakarta REST |
| `runtime` | `core`, `memory.*`, `provider` (SPI only) | concrete `provider.*` impls, `adapter.*` |
| `memory.*` | `core`, langchain4j (in `memory.chat` only) | `runtime`, `adapter.*`, `provider.*` |
| `provider` (SPI root) | `core` | langchain4j, `runtime`, `adapter.*` |
| `provider.<name>` | `core`, `provider`, langchain4j | `runtime`, `adapter.*`, other `provider.<other>` |
| `adapter.*` | `core`, `runtime`, `memory.preference` | `provider.*`, other `adapter.*`, langchain4j |
| any | — | `archtest` |

Each rule is its own ArchUnit `@Test` so a violation fails CI with a precise message.

### 3.5 Observability

- Every `AgentEvent` is routed through a runtime-internal subscriber that writes a structured SLF4J log line with `turnId` in MDC. Adapters do not log events; they consume them for transport only.
- Micrometer: one `Counter` per `AgentEvent` variant (`quark.agent.events{type=...}`), one `Timer` measuring `TurnStarted` → terminal event (`quark.agent.turn.duration`), one `Counter` for `TurnFailed{errorClass=...}`.
- `turnId` propagated into MDC at the top of `execute()` and cleared on terminal event.

## 4. Interface surfaces

### 4.1 REST + SSE

**`POST /chat`** — synchronous, full reply.
Request: `{ "sessionId": "abc", "provider": "gemini" | "nim" | null, "message": "..." }`
Response `200 application/json`:
```json
{
  "turnId": "01HXYZ...",
  "reply": "...",
  "events": [ {"type": "turnStarted", ...}, ... ]
}
```
The server subscribes to the runtime's `Multi<AgentEvent>`, accumulates `TokenEmitted` text into `reply`, and returns the full event list in `events`. `turnId` is in the body even if the turn ends in `TurnFailed`.

**`POST /chat/stream`** — Server-Sent Events.
Same request body. Response `text/event-stream`:
```
event: turnStarted
data: {"turnId":"...","sessionId":"abc","provider":"gemini","instant":"..."}

event: tokenEmitted
data: {"turnId":"...","text":"Hel"}

event: tokenEmitted
data: {"turnId":"...","text":"lo"}

event: modelCompleted
data: {"turnId":"...","totalTokens":2,"finishReason":"stop"}

event: turnCompleted
data: {"turnId":"...","durationMs":842}

event: turnEnded
data: {"turnId":"..."}
```

Rules:
- The first SSE event is **always** `turnStarted`, emitted synchronously on subscription so the client receives `turnId` before any token. This is the runtime's `TurnStarted` event forwarded directly.
- Event name = the `AgentEvent` variant name in lowerCamelCase.
- `data` is the JSON-serialised event payload.
- A final `turnEnded` event is appended by the SSE adapter (not a runtime `AgentEvent`) to give clients an unambiguous "connection done" signal regardless of whether the terminal event was `TurnCompleted` or `TurnFailed`.
- Clients ignore unknown event names — additive forward compatibility for future event types.

**`GET /chat/{sessionId}/history`** — returns the current working memory contents for inspection. Useful for tests and human debugging.

`sessionId` is caller-supplied (opaque string). No authentication in slice 1.
`provider` omitted on the request → resolved from `ProviderPreferenceStore`; absent there → `quark.agent.default-provider`.

### 4.2 Telegram

**Receive**: `PollingTelegramUpdateSource` long-polls `getUpdates` with a 30 s timeout, maintaining an offset cursor. Only `message.text` updates are processed in slice 1; other update types are dropped (logged at DEBUG).

**Commands** — recognised when the first whitespace-delimited token of `message.text` matches, case-insensitive:

| Command | Behaviour |
|---|---|
| `/start` | Replies with a brief welcome message describing supported commands. |
| `/provider` | No arg: replies with current preference and available providers. `<name>` arg: sets `ProviderPreferenceStore` for this chat, replies with confirmation. Unknown name: replies with an error and the list of valid names. |
| `/reset` | Clears `ChatMemoryStore` entries for this chat across all providers, replies with confirmation. |
| `/status` | Replies with: current provider preference, message count in working memory for the active provider, approximate prompt token estimate (4 chars ≈ 1 token heuristic for slice 1), bot uptime. |

Anything else → treated as a user message, fed into a `TurnRequest`, response streamed via `TelegramStreamRenderer`.

**Send** — `TelegramStreamRenderer` lifecycle per turn:

1. On `TurnStarted` → `sendMessage(chatId, "…")`. Record the returned `messageId`. Initialise per-message buffer = empty, `lastEditAt` = now.
2. On `TokenEmitted` → append `text` to buffer.
3. After step 2, evaluate edit gate (in this exact order):
   1. If `now - lastEditAt < throttleMs` → skip (no API call).
   2. Else if buffer is unchanged since the last edit → skip.
   3. Else if `buffer.length() > maxMessageChars` (3900) → invoke split (step 4).
   4. Else → `editMessageText(chatId, messageId, buffer)`. Set `lastEditAt = now`.
4. **Split** at the chunk boundary, executed atomically within the per-chat subscriber (no interleaving with another `TokenEmitted` for this chat):
   1. `editMessageText(chatId, messageId, buffer)` — final flush of the outgoing chunk.
   2. `sendMessage(chatId, "…")` — get new `messageId`.
   3. Reset per-message buffer = empty.
   4. Swap `messageId` to the new value.
   5. Update `lastEditAt = now`.
5. On `TurnCompleted` → one final `editMessageText(chatId, messageId, buffer)` to flush any text suppressed by throttling.
6. On `TurnFailed` → `editMessageText(chatId, messageId, buffer + "\n\n" + errorLine(errorClass, retryable))`. If that fails, fall back to `sendMessage(chatId, errorLine(...))`.
7. Any Telegram client failure during steps 3, 4, 5 → log at WARN with `turnId` and exception, skip the call, continue with subsequent ticks. The terminal flush (step 5 or 6) is the recovery point.

The first edit (step 3.4 after the first `TokenEmitted`) **replaces** the `"…"` placeholder with the buffer contents; it does not concatenate `"…" + buffer`. The placeholder is a UX artifact, not part of the conversation.

Per-chat ordering of updates is single-threaded inside the renderer (one virtual thread per active chat, or a serial executor — implementation detail). This makes the split sequence atomic without explicit locks.

## 5. Configuration

```properties
# Agent runtime
quark.agent.default-provider=gemini
quark.runtime.system-prompt.gemini=You are a helpful assistant.
quark.runtime.system-prompt.nim=You are a helpful assistant.

# Working memory bound (messages per (sessionId, provider))
quark.memory.chat.max-messages=40

# Telegram adapter
quark.telegram.enabled=${QUARK_TELEGRAM_ENABLED:false}
quark.telegram.bot-token=${TELEGRAM_BOT_TOKEN:}
quark.telegram.poll-timeout-seconds=30
quark.telegram.render.edit-throttle-ms=750
quark.telegram.render.max-message-chars=3900

# Provider: Gemini (quarkus-langchain4j-ai-gemini)
quarkus.langchain4j.ai.gemini.api-key=${GEMINI_API_KEY:}
quarkus.langchain4j.ai.gemini.chat-model.model-name=${GEMINI_MODEL:gemini-1.5-flash}

# Provider: NVIDIA NIM via OpenAI-compatible extension
quarkus.langchain4j.openai.base-url=${NIM_BASE_URL:https://integrate.api.nvidia.com/v1}
quarkus.langchain4j.openai.api-key=${NIM_API_KEY:}
quarkus.langchain4j.openai.chat-model.model-name=${NIM_MODEL:meta/llama-3.1-70b-instruct}
```

The Telegram adapter starts only if `quark.telegram.enabled=true` AND `quark.telegram.bot-token` is non-empty. Otherwise the polling source is not wired, the REST/SSE adapter remains functional.

If a provider's API key is missing, that provider's gateway bean exists but fails on first call with `PROVIDER_AUTH`. The runtime does not pre-validate keys at boot; this keeps dev mode usable with one provider configured.

## 6. Error handling and cancellation

### Error model

Every failure inside `AgentRuntime.execute()` is captured and emitted as a terminal `TurnFailed(turnId, errorClass, message, retryable)`. The `Multi<AgentEvent>` then completes normally. Renderers see exactly one terminal event of either flavour and do not need an `onError` handler.

`errorClass` enum (stable string values):

| Value | Source | `retryable` |
|---|---|---|
| `PROVIDER_TIMEOUT` | Gateway connect/read timeout | true |
| `PROVIDER_RATE_LIMITED` | HTTP 429 from provider | true |
| `PROVIDER_UNAVAILABLE` | HTTP 5xx, connection refused | true |
| `PROVIDER_AUTH` | HTTP 401/403, missing/invalid key | false |
| `PROVIDER_BAD_REQUEST` | HTTP 400, malformed prompt, exceeded context | false |
| `MEMORY_FAILURE` | ChatMemoryStore read/write throws | false |
| `INTERNAL` | Anything not mapped above | false |

`message` is short, safe for user display. Full exception chains and provider response bodies go only to the structured log against `turnId`.

### Per-stage handling inside the runtime

- `ChatMemoryStore.get` throws → `TurnFailed(MEMORY_FAILURE)`.
- `ModelGateway.stream` throws synchronously (before any `ModelEvent`) → map exception type to one of the `PROVIDER_*` classes, fall back to `INTERNAL`.
- `ModelGateway.stream` fails mid-stream → flush any already-emitted `TokenEmitted` events to the renderer, then emit `TurnFailed`. Do **not** persist the partial assistant response to memory.
- Successful completion → append `(userMessage, fullAssistantReply)` to `ChatMemoryStore`, then emit `TurnCompleted`.

Each pipeline stage is wrapped once inside the runtime. Adapters trust that the `Multi` always terminates with `TurnCompleted` or `TurnFailed`.

### No retries inside the runtime for slice 1

One attempt, fail fast. Provider retry policy (backoff, jitter, attempt cap) is a real design that deserves its own slice. The `retryable` flag tells the caller whether retrying makes sense.

### Cancellation

- `AgentRuntime.execute` returns a `Multi<AgentEvent>` whose subscription owns the in-flight model call. Cancelling the subscription cancels the gateway call and skips any pending memory write.
- SSE adapter cancels the runtime subscription when the HTTP connection closes (standard Quarkus REST + Mutiny behaviour). The `turnEnded` framing event from §4.1 is **not** sent on cancellation — the client is already gone.
- Telegram has no inbound cancel signal in slice 1; a turn runs to completion or fails. Cancel-on-block deferred.
- No terminal `AgentEvent` is emitted on cancellation — renderers detect cancellation via subscription teardown, not via an event.

### Renderer error rendering

- **SSE**: `TurnFailed` becomes `event: turnFailed` with the JSON payload, then `turnEnded`, then connection close.
- **Telegram**: see step 6 of the renderer lifecycle. Format: `<partial reply>\n\n⚠ <user-facing line>`. The user-facing line is derived from `errorClass` and `retryable` via a fixed mapping in the renderer (e.g. `PROVIDER_RATE_LIMITED` + `retryable=true` → "Rate limited. Try again in a moment."). The `message` field is captured in logs and in the sync `POST /chat` response payload but is **not** shown to Telegram users — provider-supplied messages are inconsistent and sometimes leak internal detail.

### Backpressure

Token streams arrive at low volume (tens to hundreds per second). The Telegram renderer aggregates into 750 ms edits and cannot outrun the API. SSE writes pass through directly. No special backpressure handling needed for slice 1; revisit when broadcasters or sub-character token streams appear.

## 7. Testing strategy

### Unit tests — runtime core
- Fake `ModelGateway` returning a scripted `Multi<ModelEvent>`. Assert exact `AgentEvent` sequence, ordering, payload fields.
- One test per error path: gateway throws synchronously, gateway fails after N tokens, memory store throws on read. Assert the appropriate `TurnFailed` variant and that no partial assistant response is persisted.
- Cancellation test: subscribe, cancel after first `TokenEmitted`, verify the fake gateway saw a cancellation signal and no memory write occurred.

### Unit tests — Telegram renderer
Mock `TelegramClient` recording calls. Drive the renderer with synthetic `Multi<AgentEvent>` sequences. Verify:
- `TurnStarted` → exactly one `sendMessage` with placeholder.
- First `TokenEmitted` → first `editMessageText` whose body equals the token (not `"…" + token`).
- Throttling: rapid token bursts produce at most one edit per throttle window.
- 3900-char split: long stream produces `editMessageText` (old `messageId`) → `sendMessage` (new `messageId`) → subsequent edits target the new `messageId`.
- `TurnCompleted` → final flush edit with full accumulated text for the active chunk.
- `TurnFailed` after partial tokens → final edit contains partial reply + separator + error line.
- Telegram client failure on edit → logged, next tick recovers, terminal flush succeeds.

### Unit tests — SSE adapter
- Drive `ChatResource.stream(...)` directly (no REST-assured): assert SSE event sequence matches runtime events, with `turnStarted` emitted synchronously on subscription. Verify HTTP cancel triggers runtime cancellation.

### Unit tests — commands
- Parser tests for `/provider`, `/reset`, `/status`, `/start` covering case-insensitivity, missing args, unknown provider name, unknown command.

### ArchUnit tests
- One `@Test` per boundary rule from §3.4. A violation fails the build with a precise per-rule message.

### Integration tests (separate source set, gated on env vars)
- Per-provider live test (Gemini, NIM): real `ModelGateway`, real provider API; assert a streaming turn yields ≥1 token and terminates with `TurnCompleted`. Skipped via JUnit assumption when the relevant API key env var is absent.
- Telegram polling smoke test gated on `TELEGRAM_BOT_TOKEN` AND `TELEGRAM_TEST_CHAT_ID`: send `/start`, assert a reply arrives within 10 s.

### Not tested in slice 1
- Concurrent load / many-session correctness — in-process map has trivial concurrency answers; defer to when a real backend is in.
- Provider retry behaviour — none exists yet.
- Markdown rendering — plain text only.
- Webhook update source — not implemented.

### Test infrastructure conventions
- Hand-rolled fakes for `ModelGateway` and `TelegramClient` (small interfaces; mocking framework adds noise).
- Mockito allowed for one-off stubs where genuinely cheaper.
- Plain JUnit (no `@QuarkusTest`) for runtime/renderer unit tests for speed. `@QuarkusTest` only where the Quarkus REST runtime is exercised end-to-end.

## 8. Items to verify during implementation

These are concrete uncertainties that don't change the design but need confirmation at build time:

- Exact Quarkiverse coordinates and current version of `quarkus-langchain4j-ai-gemini` and `quarkus-langchain4j-openai` compatible with Quarkus 3.35.4. Adjust the dependency list in `build.gradle.kts` accordingly.
- Whether `quarkus-telegrambots` (Quarkiverse) supports the version of Quarkus in use and works with virtual threads / Mutiny cleanly. If not, fall back to calling the Telegram Bot API directly via a Quarkus REST client. The `TelegramClient` abstraction inside `adapter.telegram` shields the rest of the code from either choice.
- Cancellation propagation through langchain4j's streaming model API. Confirm that cancelling the Mutiny `Multi` produced by the gateway actually aborts the in-flight HTTP call to the provider; if not, document the leak and decide whether it's acceptable for slice 1.
- Exact exception types raised by each provider's langchain4j integration for timeout / 429 / 401 / 400. Map them in the gateway implementations.
- Boot-time behaviour of each langchain4j provider extension when its API key is empty. The spec assumes graceful degradation (bean exists, fails on first call with `PROVIDER_AUTH`). If an extension fails fast at boot, wrap its bean in a lazy proxy inside the gateway so the app still starts with one provider configured.

## 9. Future seams (deferred, not implemented in slice 1)

- **Redis working memory** — new `ChatMemoryStore` impl in `memory.chat.redis`. No changes elsewhere.
- **Persistent provider preference** — new `ProviderPreferenceStore` impl. No changes elsewhere.
- **Webhook Telegram source** — new `TelegramUpdateSource` impl + a REST endpoint receiving Telegram updates. No changes to renderer or handler.
- **Tools** — add `ToolRegistry` and `ToolGateway`; insert a tool-execution stage in the pipeline between memory load and gateway invocation. New `AgentEvent` variants (`ToolInvoked`, `ToolReturned`). Existing renderers ignore them.
- **Planner / executor** — adds a stage before tool execution. New events (`PlanStepStarted`, `PlanStepCompleted`).
- **Episodic memory** — new `EpisodicMemoryStore` (Postgres + pgvector + embeddings). Runtime gets a retrieval stage; new events. Independent of working memory.
- **Procedural memory + reflection** — async pipeline reading from episodic store, distilling procedures, consulted by planner.
- **Markdown rendering on Telegram** — replace the plain-text path in `TelegramStreamRenderer` with a parser-backed safe-flush strategy.
- **Retry policy** — gateway-level retries with backoff, jitter, attempt cap. Configurable per provider.
- **Auth / multi-tenancy** — adapter-level concern; doesn't reach `AgentRuntime`.
