# 0001 — Event-driven `AgentEvent` stream as the runtime contract

**Status:** Accepted, 2026-05-25. Implementation deferred to Plan 4 per [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md).
**Deciders:** Daniel (project owner) + architecture brainstorming sessions.

**Related documents:**
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — "Destination" section.
- [`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](../superpowers/specs/2026-05-25-agent-runtime-mvp.md) — MVP design.
- [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md) — when this contract is introduced.

---

## Status note

This ADR describes the **destination** runtime contract. The MVP does
not implement it — the MVP streams `Multi<String>` directly out of
`ChatService`. The contract described here is introduced in Plan 4,
which extracts `AgentRuntime`, `AgentEvent`, `ModelGateway`, and
`ChatMemoryStore` from the working MVP code.

The decision is recorded now so MVP code can be shaped to refactor into
it cleanly later, and so that the architectural intent is not lost
between the MVP shipping and the refactor landing.

---

## Context

`quark` is intended to evolve beyond a simple chat interface. The
roadmap includes:

- tool execution,
- planning / executor decomposition,
- retrieval-augmented memory,
- reflection pipelines,
- multi-provider orchestration,
- additional transports and renderers.

Each of those produces information that is not plain text:

- tool invocations,
- plan step transitions,
- retrieval results,
- memory operations,
- provider lifecycle events,
- partial failures,
- reasoning metadata.

The earliest sketch modelled execution as:

```java
Multi<String>
```

That is sufficient for token streaming and almost nothing else. Once
the runtime emits anything non-text, the `Multi<String>` contract
forces a choice:

- smuggle structured data through side channels (logs, metrics,
  ad-hoc parsing), or
- redesign every adapter, renderer, and test the day tools land.

The second is a load-bearing refactor coupled with a feature change.
That coupling was judged too expensive.

---

## Decision

When extracted in Plan 4, the runtime contract becomes:

```java
Multi<AgentEvent> execute(TurnRequest request)
```

`AgentEvent` is a sealed interface representing every meaningful
runtime event during a turn. Initial variants:

- `TurnStarted`
- `MemoryLoaded`
- `ModelInvoked`
- `TokenEmitted`
- `ModelCompleted`
- `TurnCompleted`
- `TurnFailed`

Every event carries a `turnId` for correlation.

The runtime guarantees:

- exactly one terminal event (`TurnCompleted` or `TurnFailed`),
- the `Multi` completes normally afterward,
- failures are events, not `onError()`.

Transport adapters consume the same stream and project it into
transport-specific behavior:

| Adapter      | Projection                                  |
|--------------|---------------------------------------------|
| SSE          | one SSE event per `AgentEvent`              |
| Telegram     | throttled edits driven by `TokenEmitted`    |
| future       | arbitrary projections over the same stream  |

Future capabilities extend the runtime *additively* through:

- new pipeline stages,
- new `AgentEvent` variants,
- new renderers,
- new provider implementations,

without changing the execution contract itself.

`langchain4j` integrations remain implementation details inside
`provider.*` packages. They are not the architectural center of the
runtime.

---

## Consequences

### Positive

**Additive evolution.** Tools, planning, retrieval, and reflection can
be introduced without redesigning the runtime surface. The event stream
becomes the stable execution backbone.

**Transport independence.** Adapters are projection layers, not
orchestration layers. REST, SSE, Telegram, CLI, future frontends all
consume the same stream.

**Uniform observability.** Every execution stage emits structured
events correlated by `turnId`. Structured logging, event-level metrics,
execution tracing, replay tooling, and timeline visualizations all
build on the same primitive, with no transport-specific instrumentation.

**Explicit lifecycle.** Execution state is explicit — started, memory
loaded, provider invoked, streaming, completed, failed — instead of
inferred indirectly from token streams or exceptions.

**Failures become data.** Renderers no longer need separate exception
channels or transport-specific recovery logic. The invariant is "every
turn ends with exactly one terminal event."

### Negative

**Upfront complexity.** `Multi<AgentEvent>` introduces more types and
infrastructure than a minimal token stream. This is exactly why the
MVP defers it (see [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md))
— the cost is paid when there is enough working code to shape the
contract correctly.

**Renderer discipline required.** Renderers must tolerate unknown event
variants. Exhaustively switching over the sealed hierarchy would force
renderer changes every time a new event type is introduced. The
expected pattern is:

```java
if (event instanceof TokenEmitted token) { ... }
```

not exhaustive `switch`.

**Event schema is architectural surface.** Once `AgentEvent` exists,
removing or renaming variants is a breaking architectural change. That
stability requirement is accepted.

---

## Rejected alternatives

### `Multi<String>` as the runtime contract

Rejected because:

- it cannot model non-token execution stages,
- it forces structured data into side channels,
- it couples "add tools" with "redesign runtime."

`Multi<String>` is what the MVP uses *as a deliberate exception* —
ChatService returns string streams directly, and the runtime contract
is only extracted in Plan 4 once the working code justifies its shape.

### Provider-native orchestration (`AiService` as the core)

Rejected because:

- provider SDK abstractions become the architectural center,
- execution lifecycle becomes opaque to renderers,
- transport / rendering concerns leak into provider integrations,
- non-LLM stages (tools, retrieval, planning) cannot be expressed
  uniformly.

Provider SDKs are infrastructure details. Orchestration belongs to
`AgentRuntime`.

---

## Mitigations

- ArchUnit (Plan 7) enforces package boundaries so adapters cannot
  reach directly into providers or runtime internals — see
  [ADR 0002](0002-single-quarkus-module-archunit-boundaries.md).
- Renderer helper utilities may centralise "ignore unknown events"
  behaviour.
- The runtime remains transport-agnostic and provider-agnostic by
  construction.

---

## Long-term impact

This ADR defines the fundamental architectural shape of `quark` after
the walking skeleton phase ends. The premise:

> execution is modelled as an observable stream of typed events, not as
> a chat response.

Everything else in the destination architecture follows from that
premise.
