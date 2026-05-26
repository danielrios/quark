# Architecture

Orientation document. This is the high-level shape of `quark`. For full detail,
read the slice 1 design spec; for rationale on the load-bearing choices, read
the ADRs.

- **Spec (destination):** [`docs/superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md`](docs/superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md)
- **Plans (incremental path there):** [`docs/superpowers/plans/`](docs/superpowers/plans/)
- **Decisions:** [`docs/adr/`](docs/adr/)

## What it is

`quark` is an event-driven agent runtime built on Quarkus. The runtime
orchestrates a turn — load memory, call a model, persist the response — and
emits a typed stream of `AgentEvent`s. Transport adapters (REST + SSE, Telegram)
subscribe to that stream and project it into the shape their channel needs.

Tools, planning, episodic memory, and reflection are not part of the runtime
today. They are designed to slot in as additional pipeline stages emitting
additional `AgentEvent` variants — additive, not invasive.

## Pipeline

```
IncomingMessage (REST body | Telegram update)
   │
   ▼
Adapter ── builds TurnRequest(sessionId, provider?, message)
   │
   ▼
AgentRuntime.execute(TurnRequest) ──────────► Multi<AgentEvent>
   │
   ├─ load history       ChatMemoryStore.get((sessionId, provider))
   ├─ resolve provider   ProviderPreferenceStore (if unset on request)
   ├─ build ModelRequest system prompt + history + user message
   ├─ invoke gateway     ModelGateway.stream(request) → Multi<ModelEvent>
   ├─ map + accumulate   ModelEvent → AgentEvent; collect tokens
   ├─ on completion      append (user, assistant) turn to ChatMemoryStore
   └─ emit terminal      TurnCompleted or TurnFailed
                              │
                              ▼
                  ┌───────────────────────┐
                  │  Transport Renderer   │   one per adapter
                  ├───────────────────────┤
                  │ SseEventRenderer      │  Multi<AgentEvent> → SSE
                  │ TelegramStreamRenderer│  Multi<AgentEvent> → throttled edits
                  └───────────────────────┘
```

## Layered packages

```
com.quark
├── core/        Pure types (AgentEvent, Provider, ModelRequest…) — no Quarkus, no langchain4j.
├── runtime/     AgentRuntime, TurnContext, event emission.
├── memory/      ChatMemoryStore + ProviderPreferenceStore SPIs and in-process impls.
├── provider/    ModelGateway SPI + ModelEvent.
│   ├── gemini/  GeminiModelGateway wraps a langchain4j @RegisterAiService.
│   └── nim/     NimModelGateway wraps the OpenAI extension pointed at NIM.
├── adapter/
│   ├── rest/    ChatResource + SseEventRenderer.
│   └── telegram/ Polling source, message handler, stream renderer, commands.
└── config/      Property bindings.
```

Boundaries are enforced by ArchUnit tests, not by Gradle subprojects.
See [ADR 0002](docs/adr/0002-single-quarkus-module-archunit-boundaries.md).

## Reading the event stream

`AgentEvent` is a sealed interface. Every variant carries `turnId` for
correlation. The runtime emits exactly one terminal event (`TurnCompleted` or
`TurnFailed`) and then the `Multi` completes normally — renderers do not need
an `onError` handler. New event variants can be added in later slices (tools,
planning, retrieval) and existing renderers will ignore them by default.

The decision to make `AgentEvent` — rather than `String` tokens — the runtime's
primary contract is the most consequential in the project. See
[ADR 0001](docs/adr/0001-event-driven-agentevent-stream.md).

## What's actually built today

Almost nothing. The codebase is a fresh Quarkus 3.35.4 / Java 25 skeleton with
one `GreetingResource` at `GET /hello`. The runtime, both adapters, both
gateways, and the memory subsystem are designed but not yet implemented.

The build order is captured in
[`docs/superpowers/plans/`](docs/superpowers/plans/). Plan 1 is a walking
skeleton — Telegram polling + a single Gemini call, no memory, no streaming,
no abstractions. Plans 2–7 grow that into the architecture above. See
[ADR 0003](docs/adr/0003-walking-skeleton-first-plan-sequencing.md) for why
the abstractions are introduced last instead of first.

## Where things will go

| You want to… | Read |
|---|---|
| understand what's being built and why | this file, then the spec |
| understand a specific architectural choice | the relevant ADR under `docs/adr/` |
| know what's safe to implement right now | the most recent unfinished plan in `docs/superpowers/plans/` |
| add a new feature | brainstorm → spec → plan → execute (see `CLAUDE.md`) |
| work with Quarkus tooling | `CLAUDE.md` § Tooling |
