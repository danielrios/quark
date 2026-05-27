# The Quark Manifesto

Most modern AI frameworks optimize for abstraction, automation, and hidden
orchestration.

Quark optimizes for the opposite.

---

## Explicit over magical

Agent execution should be inspectable.

Every meaningful action should be visible:

* model calls,
* memory loads,
* streamed output,
* tool invocations,
* planning steps,
* failures.

A runtime should not behave like a black box.

---

## Streaming is the default

Streaming is not a UI feature.

It is the natural execution model for systems that produce tokens, tool
calls, and intermediate state over time.

Responses should arrive incrementally, propagate cleanly through transports,
and remain observable while they are happening.

---

## Small systems compose better

Quark prefers small, understandable components over layered framework
abstractions.

A flat module with five well-named files beats a four-layer architecture
nobody can hold in their head.

---

## Architecture must earn itself

Abstractions emerge from real pressure, not speculation.

The MVP stays intentionally small and direct. Runtime seams (`AgentRuntime`,
`AgentEvent`, provider gateways) appear only when there is concrete code
that justifies their shape.

Designing them up front would produce abstractions that fit a document
better than they fit the runtime.

---

## Events are the destination

The long-term runtime is event-driven because intelligent systems produce
more than text.

Tokens, tool calls, plans, memory retrievals, reflections, failures, and
decisions are all execution events. Text is only one projection of them.

The MVP does not implement this yet. The MVP streams strings. The
event-stream contract is introduced later, once real adapters exist to
shape it. See [ADR 0001](docs/adr/0001-event-driven-agentevent-stream.md)
and [ADR 0003](docs/adr/0003-walking-skeleton-first-plan-sequencing.md).

---

## Observable by design

A good agent runtime makes debugging easier, not harder.

Execution flow, timing, memory state, provider behavior, and transport
projection should remain traceable and measurable from day one — even when
the runtime itself is small.

Operational clarity is a feature.

---

## Transport is not the runtime

Telegram, REST, SSE, CLI — these are delivery mechanisms.

The runtime must remain independent of presentation. Adapters render; they
do not orchestrate.

---

## What Quark is not

Quark is not:

* an "AGI framework",
* a general-purpose abstraction layer,
* a low-code automation platform,
* a collection of magical chains.

The goal is a compact execution runtime where reasoning, orchestration,
streaming, and memory remain explicit.

---

## Build the smallest thing that can evolve

Quark starts as a tiny conversational service: receive a message, call
Gemini, stream a reply, remember a few turns.

The ambition is long-term. The implementation stays grounded.

Every increment is end-to-end runnable. Every abstraction is extracted
from working code, not invented for it.
