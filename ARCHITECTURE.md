# Architecture

This document is the canonical technical description of Quark's current
implementation and the architectural direction that follows from it.

For principles, read [`MANIFESTO.md`](MANIFESTO.md). For the long-term product
thesis, read [`docs/vision/runtime-platform.md`](docs/vision/runtime-platform.md).

---

## Current architecture

Quark currently runs as a Java + Quarkus application. The runtime seams were
extracted from a working Telegram -> Gemini conversational loop rather than
designed speculatively.

```text
Telegram update
      │
      ▼
Telegram adapter
      │
      ▼
TurnRequest
      │
      ▼
AgentRuntime.execute(TurnRequest)
      │
      ├── load history through ChatMemoryStore
      ├── build prompt
      ├── invoke ModelGateway
      ├── map streamed chunks to AgentEvent.TokenEmitted
      ├── persist the completed turn
      └── emit one terminal AgentEvent
              │
              ▼
       Telegram renderer
```

The current public runtime contract is:

```java
Multi<AgentEvent> execute(TurnRequest request)
```

`AgentEvent` currently contains:

- `TurnStarted`
- `MemoryLoaded`
- `ModelInvoked`
- `TokenEmitted`
- `ModelCompleted`
- `TurnCompleted`
- `TurnFailed`

Every event carries a `turnId`. A turn ends with one terminal event and then
the stream completes normally.

### Current package boundaries

```text
com.quark
├── core/        — execution contracts and shared types
├── runtime/     — AgentRuntime orchestration
├── memory/      — ChatMemoryStore + in-memory implementation
├── provider/    — ModelGateway + Gemini implementation
└── adapter/     — transport adapters and renderers
```

The useful boundaries are conceptual:

- transports project execution; they do not orchestrate it;
- runtime code should not depend on concrete transports;
- provider-specific code stays behind a provider boundary;
- memory stays replaceable behind a store boundary;
- execution events remain transport-neutral.

---

## Current framework coupling

The runtime is not framework-independent yet.

Today:

- `AgentRuntime` is CDI-managed (`@ApplicationScoped`, `@Inject`);
- runtime streaming exposes SmallRye Mutiny `Multi`;
- `ModelGateway` exposes `Multi<String>`;
- Quarkus owns application startup, DI, configuration, and host lifecycle;
- Quarkus logging is used inside the runtime;
- Gemini uses Quarkus LangChain4j integration;
- the build is a single Quarkus application module.

These are properties of the current walking skeleton, not architectural
principles to preserve indefinitely.

The single-module decision is recorded in
[ADR 0002](docs/adr/0002-single-quarkus-module-archunit-boundaries.md). If the
framework-independent runtime invalidates that decision, a new ADR should
supersede it instead of rewriting history.

---

## Architectural direction

The destination is not "replace Quarkus with another application framework."

The destination is:

```text
Host application
       │
       ▼
   Quark runtime
```

where the host may be Spring Boot, Quarkus, Ktor, Micronaut, plain JVM, a CLI
process, or another compatible JVM environment.

Three invariants guide this transition:

```text
Transport != Runtime
Framework != Runtime
Agent Framework != Runtime
```

Quarkus may become an optional first-class integration later. Spring AI,
LangChain4j, provider SDKs, and custom agent logic may also sit above or
alongside the runtime. The exact adapter shapes are intentionally undecided.

---

## Execution semantics

The runtime models a turn as a lifecycle rather than only a final response.

Current execution already exposes:

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

Future production pressure may justify additional semantics such as provider
selection, tool requests, policy evaluation, approvals, cancellation,
checkpoints, or resume. Those names are examples, not commitments to a schema.

The durable principle is that meaningful runtime behavior should remain
explicit and observable.

---

## Observability, control, and reliability boundaries

These are directions, not a claim that all corresponding features exist.

### Observability

Current state:

- `turnId` correlation exists;
- lifecycle state is available through `AgentEvent`;
- logging exists.

Direction:

- measure execution phases rather than only total request time;
- expose provider/model/tool metadata when those concepts exist;
- integrate with OpenTelemetry-oriented tooling;
- keep proprietary telemetry optional.

### Control

If real use cases require permissions, approvals, budgets, provider
restrictions, timeouts, or cancellation, those decisions should become
explicit runtime semantics rather than hidden prompt instructions.

No policy DSL or approval subsystem is defined yet.

### Reliability

Replay, checkpoint/resume, deterministic test surfaces, or integration with
durable execution systems may become useful later. Quark should not rebuild a
general-purpose distributed workflow engine by default.

---

## Near-term migration direction

The migration decision, its increments, and the framework-independence milestone criteria
are recorded in [ADR 0008](docs/adr/0008-framework-independent-runtime-and-kotlin-migration.md).

Active sequence (sequencing labels, not module designs):

```text
Phase 0     — restore green baseline            (done, 2026-08-23)
Migration 1 — Kotlin and Coroutines build support   (next)
Migration 2 — lock streaming/cancellation semantics (likely; closes G-1/G-2)
Migration 3 — framework-neutral Kotlin runtime contracts (likely)
later       — runtime implementation, framework-ownership removal,
              provider-boundary evolution, host integrations
```

The work itself proceeds incrementally:

1. introduce Kotlin alongside Java;
2. preserve current runtime behavior through tests;
3. move public streaming contracts away from Quarkus/Mutiny-specific types;
4. migrate streaming semantics toward Kotlin Coroutines / `Flow`;
5. make runtime construction and lifecycle independent from CDI;
6. remove Quarkus from the runtime core gradually;
7. retain Quarkus as a potential optional host integration;
8. add new production semantics only when real use cases justify them.

This sequence does not pre-decide:

- final Gradle module topology;
- PF4J or another plugin framework;
- a harness layer;
- a policy DSL;
- multi-agent orchestration;
- a workflow engine;
- cloud APIs;
- exact Spring AI / LangChain4j / Quarkus adapter shapes.

Those decisions should be made through implementation pressure and ADRs.

---

## Historical plan sequence

The original MVP sequence remains useful historical context:

| Plan | Historical status | Purpose |
| --- | --- | --- |
| 1 | landed | Telegram + Gemini walking skeleton |
| 2 | landed | bounded conversation memory + `/reset` |
| 3 | landed | streaming Telegram responses |
| 4 | landed | extract `AgentRuntime`, `AgentEvent`, `ModelGateway`, `ChatMemoryStore` |
| 5 | **abandoned before completion** | second provider + provider selection/status |
| 6 | historical plan, re-evaluate | REST/SSE adapter |
| 7 | historical plan, re-evaluate | architecture enforcement + metrics |

Plans 5–7 were defined before the framework-independent runtime direction.
They remain historical design intent, but they should not be executed
mechanically as the current roadmap. Their requirements need to be
re-evaluated against the new migration direction.

Plan 5's in-flight implementation (NIM provider + provider preference) was
discarded after Phase 0 restored the pre-Plan-5 baseline. The experiment was
small, incomplete, non-compiling, and coupled provider selection more deeply
to CDI — exactly the direction ADR 0008 is reversing. NIM itself remains a
candidate second-provider integration once the neutral provider boundary
exists; if that need returns, it should be implemented fresh against the new
boundary rather than porting the abandoned design.

For mutable implementation state, use [`docs/progress.md`](docs/progress.md).

---

## Historical decisions

Key records include:

- [ADR 0001](docs/adr/0001-event-driven-agentevent-stream.md) — why execution became a typed event stream;
- [ADR 0002](docs/adr/0002-single-quarkus-module-archunit-boundaries.md) — why the current implementation chose a single Quarkus module;
- [ADR 0003](docs/adr/0003-walking-skeleton-first-plan-sequencing.md) — why runtime abstractions were extracted only after the conversational loop worked;
- [ADR 0007](docs/adr/0007-agent-runtime-owns-conversation-memory.md) — why the runtime owns conversation memory semantics;
- [ADR 0008](docs/adr/0008-framework-independent-runtime-and-kotlin-migration.md) — the framework-independent Kotlin/JVM migration decision and milestone acceptance criteria.

ADRs are historical records. When assumptions change, supersede decisions;
do not edit history.
