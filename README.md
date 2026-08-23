# quark

A small, embeddable, streaming-first agent execution runtime for the JVM.

> Build agents with anything. Run them with control.

Quark models an agent turn as an observable execution lifecycle rather than a
`prompt -> string` call. Its focus is the part that becomes difficult after an
agent prototype works: understanding and controlling how that agent executes
inside a real system.

Quark is experimental. The current implementation is still a Java + Quarkus
application and is not framework-independent yet. The framework-independent
runtime is the next architectural transition, not a capability claimed today.

- [`MANIFESTO.md`](MANIFESTO.md) — engineering principles
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — current implementation, coupling, history, and migration direction
- [`docs/vision/runtime-platform.md`](docs/vision/runtime-platform.md) — long-term product thesis

---

## Why Quark exists

Building a proof-of-concept agent is becoming easy. Operating agent behavior
inside production systems is harder.

Teams eventually need to answer questions such as:

- what happened during this turn?
- which model/provider participated?
- which actions were requested?
- why was an action allowed, blocked, or held for approval?
- where did time and cost go?
- can execution be cancelled, diagnosed, tested, or recovered?
- can different agent frameworks share common execution semantics?

Quark aims at that layer:

```text
Spring AI / LangChain4j / provider SDKs / custom agent logic
                         │
                         ▼
                       Quark
                         │
                  agent execution
```

The named integrations above are examples of the intended ecosystem
relationship. They are not implemented modules today.

---

## What runs today

The repository currently ships a working Telegram -> Gemini walking skeleton
with:

- Telegram long polling;
- Gemini through Quarkus LangChain4j;
- bounded per-session conversation memory;
- streaming replies through throttled Telegram edits;
- `/reset`;
- `AgentRuntime` as the orchestration point;
- a typed `AgentEvent` lifecycle stream;
- `ModelGateway` and `ChatMemoryStore` boundaries;
- per-turn `turnId` correlation.

The exact current coupling to Quarkus, CDI, Mutiny, and LangChain4j is kept in
[`ARCHITECTURE.md`](ARCHITECTURE.md), so the README does not need to duplicate
implementation details that are expected to change.

---

## Running Quark today

### Requirements

- JDK 25
- a Gemini API key
- a Telegram bot token

### Configuration

```bash
export GEMINI_API_KEY=your-gemini-api-key
export TELEGRAM_BOT_TOKEN=your-token-from-botfather
```

Telegram is enabled by default. It can be disabled with:

```bash
export QUARK_TELEGRAM_ENABLED=false
```

### Run

For normal local development:

```bash
./gradlew quarkusDev
```

The Quarkus Dev UI is available at:

```text
http://localhost:8080/q/dev/
```

When working through the repository's Claude Code harness, use the
`quarkus-agent` MCP commands (`quarkus_start`, `quarkus_status`,
`quarkus_logs`) instead of starting a second foreground dev process. See
[`CLAUDE.md`](CLAUDE.md).

### Build and test

```bash
./gradlew build
./gradlew test
```

Tests require no real secrets: the test profile disables Telegram polling and
uses a dummy Gemini key.

The current Quarkus application also supports its existing native-image build:

```bash
./gradlew build -Dquarkus.native.enabled=true
```

Native-image support is a property of the current host application, not a
commitment about the future framework-independent runtime.

### Telegram commands

| Command | Status | Behavior |
| --- | --- | --- |
| `/reset` | available | clears conversation memory for the session |
| `/start` | historical/planned | not part of the current shipped command set |
| `/status` | historical/planned | not part of the current shipped command set |

---

## Execution model

A turn is a lifecycle, not only a final response.

The current runtime already exposes typed events such as:

```text
TurnStarted
    ↓
MemoryLoaded
    ↓
ModelInvoked
    ↓
TokenEmitted ...
    ↓
ModelCompleted
    ↓
TurnCompleted | TurnFailed
```

Future execution semantics may include tools, policies, approvals,
cancellation, or recovery, but those capabilities are intentionally not
specified from documentation alone. See [`ARCHITECTURE.md`](ARCHITECTURE.md)
for the canonical execution model and migration direction.

---

## Scope

Quark is deliberately not:

- a personal assistant product;
- an all-in-one agent platform;
- a replacement for agent frameworks or provider SDKs;
- an application or web framework;
- a general-purpose workflow engine;
- a mandatory dependency-injection container.

Quark should integrate with adjacent systems rather than absorb their
responsibilities.

---

## Near-term direction

The next engineering phase is focused on extracting the runtime semantics from
the framework that currently hosts them: Kotlin alongside Java,
framework-neutral contracts, Kotlin Coroutines / `Flow`, and an embeddable
runtime lifecycle.

The exact sequence and unresolved architecture questions live in
[`ARCHITECTURE.md`](ARCHITECTURE.md). Long-term product possibilities live in
[`docs/vision/runtime-platform.md`](docs/vision/runtime-platform.md).

Architecture must earn itself.

---

## Repository layout

```text
README.md            — project entry point and current usage
MANIFESTO.md         — stable engineering principles
ARCHITECTURE.md      — current technical truth + migration direction
CLAUDE.md            — operating rules for Claude Code sessions
docs/
├── adr/             — historical and load-bearing architectural decisions
├── progress.md      — mutable implementation progress ledger
├── superpowers/     — specs and implementation plans
└── vision/          — long-term product/architecture direction
.claude/             — Claude Code development harness
```

---

## Why "quark"

The name began as a nod to Quarkus. It also fits the project's desired
character independently: a small fundamental building block for larger agent
systems.

---

## Status

Quark is experimental and evolving. The typed event-driven runtime exists
today; framework independence and richer production execution semantics are
future work.
