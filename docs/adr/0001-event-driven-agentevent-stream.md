# 0001 — Event-driven `AgentEvent` stream as the runtime contract

**Status:** Accepted, 2026-05-25
**Deciders:** Daniel (project owner) + brainstorming session
**Context refs:** [`docs/superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md`](../superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md) §3.3

## Context

`quark` will eventually support tools, planning, retrieval-augmented memory,
and an async reflection pipeline. Each of those stages produces *information*
beyond text — tool invocations, plan steps, retrieval hits, reflection
artefacts — that adapters (REST/SSE, Telegram, future UIs) may want to
project differently.

The earliest sketch of the runtime modelled a turn as `Multi<String>`
(streaming chat tokens). That works for a token-by-token chat UI and almost
nothing else: there is no place to emit `ToolInvoked`, no place for
`PlanStepStarted`, no way for a renderer to distinguish "model finished"
from "the next planning step is starting". Adding those stages to a
`Multi<String>` runtime would either smuggle structured data into
out-of-band channels or force a breaking change later when the absent
abstraction finally bites.

The alternative considered seriously: keep `Multi<String>` for slice 1 and
swap to a typed event stream when tools land in a later slice. Rejected
because every adapter and every test would be rewritten at that point, and
the slice that introduces tools would mix "add tools" with "redesign the
core contract" — two concerns in one change.

## Decision

The runtime's public contract is `Multi<AgentEvent> execute(TurnRequest)`,
where `AgentEvent` is a sealed interface with one variant per kind of
information the runtime emits during a turn. Slice 1 ships seven variants:
`TurnStarted`, `MemoryLoaded`, `ModelInvoked`, `TokenEmitted`,
`ModelCompleted`, `TurnCompleted`, `TurnFailed`. Every variant carries
`turnId` for correlation. The terminal event is always exactly one of
`TurnCompleted` or `TurnFailed`, after which the `Multi` completes
normally (no `onError`).

Transport adapters subscribe to the stream and project events into their
channel:

- SSE emits one SSE event per `AgentEvent`, named after the variant.
- Telegram accumulates `TokenEmitted` payloads and edits a chat message on
  a throttled cadence, finalising on the terminal event.

New event variants added in later slices (tools, planning, retrieval) are
ignored by existing renderers — additive forward compatibility.

LangChain4j's `@RegisterAiService` is permitted only *inside* the
`provider.*` packages, where it is an implementation detail of a
`ModelGateway`. It is never exposed as the runtime's foundation.

## Consequences

### Positive
- Adding tools, planning, or retrieval in later slices is additive: new
  pipeline stages emit new event variants, existing renderers and tests are
  unchanged.
- Renderers stay simple — one subscription, no exception handling, no
  parallel out-of-band channels.
- Observability is uniform: a single runtime subscriber logs every event
  with `turnId` in MDC; Micrometer counters are one-per-variant.
- The `Multi`-completes-normally-on-failure invariant means cancellation
  and termination paths are unambiguous.

### Negative
- More upfront types and machinery than `Multi<String>`. The walking
  skeleton (plan 1) deliberately does not pay this cost — see
  [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md) — but the
  cost lands in plan 4 when the abstractions are extracted.
- "All renderers ignore unknown events" is a discipline rule, not enforced
  by the type system. A renderer that switches over the sealed interface
  and demands exhaustiveness would break on every new variant. Renderers
  must use a non-exhaustive pattern.
- `AgentEvent` is part of the public-ish API of the runtime module.
  Renaming or removing variants is a breaking change to every adapter.

### Mitigations
- ArchUnit ([ADR 0002](0002-single-quarkus-module-archunit-boundaries.md))
  enforces that the runtime never depends on a concrete adapter, so the
  blast radius of contract changes is bounded to renderers we control.
- A renderer-base-class or helper utility can absorb the
  ignore-unknown-events pattern so individual renderers do not each
  reimplement it.
