# Architecture

Three states live in this document, and they are deliberately
different:

* **Bootstrap state (right now)** — a generated Quarkus skeleton.
  Almost no application code.
* **MVP target (Plans 1–3)** — the conversational runtime shape.
  A flat module, direct provider calls, string streaming. Small enough
  to hold in your head.
* **Destination (Plans 4–7+)** — the event-driven runtime. Layered
  packages, `AgentRuntime` orchestration, typed `Multi<AgentEvent>`
  stream. Designed to support tools, planning, reflection, and
  multi-provider orchestration without rewriting the core.

[ADR 0003](docs/adr/0003-walking-skeleton-first-plan-sequencing.md)
explains why the project ships the first one before reaching for the
second.

Detail lives in:

* MVP design spec — [`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md)
* Decision records — [`docs/adr/`](docs/adr/)
* Long-term direction — [`docs/vision/runtime-platform.md`](docs/vision/runtime-platform.md)
* Implementation plans — [`docs/superpowers/plans/`](docs/superpowers/plans/)

---

# Bootstrap state — right now

The current source tree contains:

* a generated `GreetingResource` exposing `GET /hello`,
* `quarkus-langchain4j-core` on the classpath,
* an empty `application.properties`,
* the harness under `.claude/`, Spotless, release-please, and CI.

There is no `ChatService`, no Telegram code, no Gemini wiring, no
memory. Everything below the "MVP target" header is **what the next
plans build**, not what runs today.

---

# MVP target — Plans 1–3

The MVP delivers a single conversational loop, end-to-end. No runtime
abstractions, no provider SPI, no event stream, no ArchUnit boundaries.

> Until Plans 1–3 land, none of the names in this section exist in the
> source tree. They are the target shape, recorded so contributors
> agree on what is being built before code starts moving.

## Pipeline

```text
Incoming message (Telegram update | POST /chat)
        │
        ▼
ChatService.chat(sessionId, message)
        │
        ├─ load history from ChatMemory (in-memory, bounded)
        │
        ├─ append user message
        │
        ├─ GeminiChatClient.stream(history) → Multi<String>
        │
        ├─ accumulate tokens
        │
        ├─ persist assistant reply into ChatMemory
        │
        └─ return reply
            │
            ▼
    Transport-specific rendering
    ├─ REST  → full body
    ├─ SSE   → `data: <token>` frames
    └─ Telegram → throttled message edits
```

One service. One provider. One memory implementation. One shape of
streaming output (`Multi<String>`).

## Package layout

```text
com.quark
├── chat/
│   ├── ChatService.java       — orchestration: memory → model → persist
│   ├── ChatMemory.java        — Map<sessionId, List<ChatMessage>>, bounded
│   ├── ChatMessage.java       — role + content record
│   └── GeminiChatClient.java  — thin langchain4j wrapper
├── telegram/
│   ├── TelegramBotRunner.java — startup hook + polling loop on virtual thread
│   ├── TelegramMessageHandler.java — dispatch updates to ChatService
│   └── TelegramRenderer.java  — throttled edits, message splitting
├── rest/
│   └── ChatResource.java      — POST /chat, POST /chat/stream, GET /history/{id}
├── config/
│   └── AppConfig.java         — config record(s), Telegram enable flag
└── shared/
    └── (helpers, if any)
```

Flat by design. There is no `core/runtime/provider/adapter` split yet
because nothing inside the MVP would benefit from one.

## What the MVP intentionally does not do

| Concern                  | Status in MVP                                |
|--------------------------|----------------------------------------------|
| `AgentRuntime`           | not present; `ChatService` orchestrates directly |
| `AgentEvent` / typed stream | not present; runtime emits `Multi<String>` |
| Provider abstraction     | not present; `GeminiChatClient` is concrete |
| Second provider (NIM)    | deferred; arrives with the runtime refactor |
| ArchUnit boundaries      | deferred to the same refactor                |
| Micrometer metrics       | logs + correlation id only                  |
| Tools, planning, reflection | not in scope                              |
| Episodic memory, vector search | not in scope                           |
| Webhook Telegram mode    | deferred                                     |
| Retry policies           | one attempt, fail fast                       |
| Multi-tenancy            | not in scope                                 |

This list is not aspirational; it is the contract for what does **not**
get built before the conversational loop is validated.

## Observability in the MVP

Minimal but real, once Plans 1–3 land:

* structured logs, JSON-shaped where Quarkus defaults allow,
* per-turn `turnId` (correlation id) on every log line in the turn,
* provider latency logged on each model call.

No metrics pipeline yet. The destination metrics design lives in the
ADRs and arrives with the runtime refactor.

---

# Destination — the event-driven runtime

This is where the codebase is headed, **not** what it looks like today.
The design is documented for two reasons:

1. So the MVP code can be shaped to refactor cleanly into it later.
2. So that contributors reading the ADRs do not mistake them for the
   current state of the repository.

## Pipeline

```text
IncomingMessage (REST body | Telegram update)
        │
        ▼
Adapter → TurnRequest(sessionId, provider?, message)
        │
        ▼
AgentRuntime.execute(TurnRequest) : Multi<AgentEvent>
        │
        ├─ load history     (ChatMemoryStore)
        ├─ resolve provider (ProviderPreferenceStore)
        ├─ build request    (system prompt + history + message)
        ├─ invoke gateway   (ModelGateway.stream)
        ├─ map provider events → AgentEvent
        ├─ accumulate assistant text
        ├─ persist completed turn
        └─ emit terminal event (TurnCompleted | TurnFailed)
                │
                ▼
        Transport renderer
        ├─ SSE — one frame per AgentEvent
        ├─ Telegram — throttled edits driven by TokenEmitted
        └─ future transports
```

Future stages — tool execution, planning, retrieval, reflection — slot
in as additional pipeline stages emitting additional `AgentEvent`
variants. They extend the stream; they do not reshape it.

## Package layout

```text
com.quark
├── core/        — pure contracts and shared types. No Quarkus, no langchain4j, no transports.
├── runtime/     — AgentRuntime orchestration, event emission, MDC propagation.
├── memory/      — ChatMemoryStore SPI + in-process implementation.
├── provider/    — ModelGateway SPI + provider implementations.
│   ├── gemini/
│   └── nim/
├── adapter/     — transport adapters and renderers.
│   ├── rest/
│   └── telegram/
└── config/      — wiring.
```

Single Quarkus module. Boundaries enforced by ArchUnit tests, not by
Gradle subprojects — see
[ADR 0002](docs/adr/0002-single-quarkus-module-archunit-boundaries.md).

## Event model

`AgentEvent` is a sealed interface representing the runtime lifecycle.
Initial variants:

* `TurnStarted`
* `MemoryLoaded`
* `ModelInvoked`
* `TokenEmitted`
* `ModelCompleted`
* `TurnCompleted`
* `TurnFailed`

Every event carries a `turnId`. The runtime guarantees exactly one
terminal event (`TurnCompleted` or `TurnFailed`), after which the
`Multi` completes normally. Renderers therefore consume a deterministic
lifecycle and do not need transport-specific error channels.

Rationale: [ADR 0001](docs/adr/0001-event-driven-agentevent-stream.md).

## Boundaries

| Layer       | May depend on                                    | May not depend on                            |
|-------------|--------------------------------------------------|----------------------------------------------|
| `core`      | nothing framework-specific                       | Quarkus, langchain4j, Jakarta REST, Telegram |
| `runtime`   | `core`, `memory.*`, `provider` SPI               | concrete `provider.*`, `adapter.*`           |
| `provider.<name>` | `core`, `provider` SPI, langchain4j         | `runtime`, `adapter.*`, other providers      |
| `adapter.*` | `core`, `runtime`, `memory.preference`           | `provider.*`, other adapters, langchain4j    |
| `memory.*`  | `core` (langchain4j only inside `memory.chat`)   | `runtime`, `adapter.*`, `provider.*`         |

ArchUnit tests live under `src/test/java/.../archtest` and fail the
build on violation.

## Observability

* Structured logs correlated by `turnId`.
* Micrometer counters keyed by `AgentEvent` variant.
* Per-turn timer.
* Future tracing hooks inherit the same correlation id.

---

# Bridge — how the MVP becomes the destination

The MVP and the destination are connected by an explicit plan sequence
in [ADR 0003](docs/adr/0003-walking-skeleton-first-plan-sequencing.md):

| Plan | What lands                                                    | Abstractions introduced                                |
|------|---------------------------------------------------------------|--------------------------------------------------------|
| 1    | Telegram polling + Gemini, one message in / one message out  | None. `@RegisterAiService` directly.                   |
| 2    | In-process working memory + `/reset`                          | None. Memory on the dispatcher.                        |
| 3    | Telegram streaming via throttled edits                        | None. Streaming stays Telegram-specific.               |
| 4    | Extract `AgentRuntime`, `AgentEvent`, `ModelGateway`, `ChatMemoryStore` | Core runtime seams.                                    |
| 5    | NIM provider + `/provider` + `/status`                        | `ProviderPreferenceStore` + second gateway.            |
| 6    | REST + SSE adapter                                            | Second transport validates the event stream.           |
| 7    | ArchUnit boundaries + Micrometer observability                | Structural enforcement + operational visibility.       |

Plan 4 is the architectural inflection point. By the time it lands,
Plans 1–3 have produced enough real behaviour (memory, streaming,
provider interaction, Telegram constraints) that the runtime seams can
be extracted from working code instead of speculated into existence.

---

# Where things go next

| Goal                                | Read                                                              |
|-------------------------------------|-------------------------------------------------------------------|
| Understand what runs today          | this document, "Bootstrap state" section                          |
| Understand what's being built next  | the MVP design spec, and the "MVP target" section above           |
| Understand architectural decisions  | `docs/adr/`                                                       |
| Understand the long-term direction  | [`docs/vision/runtime-platform.md`](docs/vision/runtime-platform.md), [`MANIFESTO.md`](MANIFESTO.md) |
| Know what is safe to implement now  | the next unfinished plan in `docs/superpowers/plans/`             |
| Work with tooling / Claude Code     | [`CLAUDE.md`](CLAUDE.md) and [ADR 0004](docs/adr/0004-claude-code-harness.md) |
| Add a new architectural capability  | spec → ADR → plan → implementation                                |
