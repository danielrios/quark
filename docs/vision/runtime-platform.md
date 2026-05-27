# Long-term direction

This document describes where `quark` is headed *after* the MVP is
working. It is not a roadmap with dates, and none of what follows
implies an active workstream — see [README](../../README.md) and
[ARCHITECTURE](../../ARCHITECTURE.md) for what is actually being built
right now.

The MVP exists to validate the conversational loop. This document
exists to make the destination explicit so the MVP can be shaped to
evolve into it without rewrites.

---

## Why a runtime, not a chatbot

A typical AI application couples six concerns tightly together:

* transport (HTTP, WebSocket, Telegram, ...),
* orchestration (what happens in what order),
* provider SDKs (OpenAI, Gemini, Anthropic, ...),
* memory (history, context, retrieval),
* rendering (how the response is presented),
* business logic.

That coupling produces systems that demo well and are painful to
change. Adding a tool means touching transport. Switching providers
means touching rendering. Adding planning means rewriting the response
loop.

`quark` separates these concerns through one structural decision:

> the runtime emits a typed stream of execution events; everything else
> is a consumer of that stream.

A turn is not "send prompt, return string." A turn is a typed
execution lifecycle — model invocation, token output, memory load,
eventual tool call or plan step — emitted as observable events. Transport
adapters project the stream into whatever shape their channel needs.

The MVP does not implement this yet. It streams strings. The
event-stream contract is the destination, not the starting point.
[ADR 0001](../adr/0001-event-driven-agentevent-stream.md) records the
decision; [ADR 0003](../adr/0003-walking-skeleton-first-plan-sequencing.md)
records the sequencing.

---

## Design principles

These principles guide both the MVP and the destination. They are not
slogans; each one constrains specific decisions.

### Streaming-first

Streaming is the native execution model, not an enhancement layer.

The runtime is built around incremental token flow, lifecycle events,
cancellation, partial execution, and transport projection. Adapters do
not block on full responses; they react to events as they arrive.

### Observable by default

The runtime emits structured execution events as part of its contract,
not as instrumentation glued on later. Logs, metrics, traces, and
replay tooling all consume the same event stream. Operational clarity
is built into the architecture rather than retrofitted.

### Additive evolution

New capabilities — tools, planning, retrieval, reflection, retries,
multi-agent coordination — must compose into the runtime by introducing
new pipeline stages and new event variants. They must not require
reshaping existing execution flows.

If a planned capability would require touching `AgentRuntime`'s public
contract, that is a signal that either the capability is wrong, or the
contract is.

### Transport-agnostic execution

REST, SSE, Telegram, CLI, future UIs — these are all projections of the
same event stream. The runtime does not know which transport is
rendering its events, and providers do not know either.

### JVM-native

Java 25 and Quarkus, with virtual threads, Mutiny streams, and strong
typing. The point is not to reimplement Python frameworks on the JVM;
it is to take advantage of what the JVM offers — predictable
concurrency, structured types, mature observability tooling — for
something this domain has not historically had.

---

## What the destination enables

Once the runtime seams from [ADR 0003](../adr/0003-walking-skeleton-first-plan-sequencing.md)
Plan 4 are in place, the following become additive changes rather than
rewrites:

* tool execution as a runtime stage, with `ToolInvoked` /
  `ToolCompleted` event variants;
* planner / executor decomposition over the same event stream;
* memory backends behind `ChatMemoryStore` (Redis, Postgres, vector
  stores);
* reflection loops that consume one turn's events and produce input for
  the next;
* multiple providers behind `ModelGateway`, with per-session preference
  and failover;
* persistent event storage and turn replay for debugging.

Each of these is a deferred concern, not a planned feature. None of
them get built before the conversational loop is validated and the
runtime seams have been extracted.

---

## What `quark` is not

Stated explicitly so it stays explicit:

* not a no-code or low-code platform,
* not a prompt wrapper,
* not a frontend product,
* not a general-purpose orchestration framework,
* not an attempt to build an "AGI platform."

A runtime. Small enough to read; structured enough to grow.

---

## Why the name

A quark is one of the elementary constituents of matter — small, never
observed in isolation, responsible for everything larger. The runtime
follows the same idea: small execution units, composable stages,
observable interactions, emergent behaviour from orchestration.

Also a direct nod to Quarkus.
