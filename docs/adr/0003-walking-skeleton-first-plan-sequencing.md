# 0003 — Walking-skeleton-first plan sequencing

**Status:** Accepted, 2026-05-25
**Deciders:** Daniel + brainstorming session
**Context refs:** [`docs/superpowers/plans/2026-05-25-plan-1-telegram-gemini-walking-skeleton.md`](../superpowers/plans/2026-05-25-plan-1-telegram-gemini-walking-skeleton.md)

## Context

The slice 1 design ([spec](../superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md))
defines a real runtime: `AgentRuntime`, `AgentEvent`, `ModelGateway`,
`ChatMemoryStore`, `ProviderPreferenceStore`, two transport adapters,
two model providers, ArchUnit-enforced boundaries, Micrometer
observability, and the test infrastructure to cover all of it.

Two plausible ways to build that:

1. **Top-down.** Implement the abstractions first — `AgentEvent`,
   `AgentRuntime`, the SPIs — with stub implementations behind them,
   then fill in real providers and adapters.
2. **Bottom-up walking skeleton.** Build the smallest end-to-end working
   thing — Telegram polling, one model, no abstractions, no memory —
   then *refactor* it toward the spec across several subsequent plans,
   introducing each abstraction only when there is enough real code to
   justify its shape.

The top-down approach is the natural reflex when there is a strong
design document. It also has a well-documented failure mode: the
abstractions get designed in the absence of real callers, so they fit
the spec but not the code that has to use them. The shape that "looks
right" in the design crystallises before two or three real consumers
have pulled on it. The first non-trivial requirement that doesn't fit
forces either a leaky workaround or a rewrite of the abstraction with
much of the dependent code now needing rework.

Walking skeleton is the explicit antidote: build the dumbest thing that
works, then extract the abstractions when the duplication or rigidity
they would solve actually exists. The cost is that the early code looks
shabby and obviously incomplete; the payoff is that every abstraction
is introduced with at least two real call sites driving its shape.

Project owner asked for "very small, with few files and very simple,
without much architecture" for the first plan, then "refactor and create
new plans, increasing the complexity". This is the explicit walking
skeleton request.

## Decision

The codebase is built in seven planned increments, each its own plan
document under `docs/superpowers/plans/`:

| Plan | Increment | Abstractions introduced |
|---|---|---|
| 1 | Telegram polling + Gemini, single message in / single message out | None. Direct `@RegisterAiService` injection. |
| 2 | In-process working memory, `/reset` command | None. Memory is a field on the dispatcher. |
| 3 | Telegram streaming via throttled message edits | None new. Streaming is internal to the Telegram path. |
| 4 | **Extract** `AgentRuntime`, `AgentEvent`, `ModelGateway`, `ChatMemoryStore` SPI | All four. This plan is a refactor, not a feature. |
| 5 | NIM provider, `ProviderPreferenceStore`, `/provider` and `/status` commands | `ProviderPreferenceStore` SPI. Second `ModelGateway` impl. |
| 6 | REST + SSE adapter | None new. Second renderer drives event-stream design validation. |
| 7 | ArchUnit boundaries + Micrometer observability | None new. Locks the structure in place. |

Plan 4 is the inflection point: by the time it lands, plans 1–3 have
produced real code in the shape "Telegram path + Gemini + memory +
streaming" with no internal seams. Plan 4 extracts the seams the spec
prescribes, with the working slice as the safety net (tests pass before
and after). Plans 5–7 then exercise the seams from multiple call sites,
which is the only way to learn whether the extracted shape is actually
the right one.

## Consequences

### Positive
- Each plan ships working software that can be exercised end-to-end.
  Regressions are caught immediately by the live test path, not
  discovered months later when "scaffolding" code finally meets real
  load.
- Abstractions are introduced after at least one concrete implementation
  exists, so their shape is informed by real code rather than design
  guesswork. By plan 5, every SPI extracted in plan 4 has at least two
  consumers driving its evolution.
- The walking skeleton is reviewable end-to-end as a unit — it does one
  thing, in a few hundred lines, and can be deleted entirely if the
  design turns out wrong.
- Plan 1's deliberately tiny scope means the project can demo something
  real in hours, not weeks. This is high-value in an exploratory phase
  where the design itself may shift.

### Negative
- Plans 1–3 produce code that does *not* match the spec's package
  layout, naming, or architecture. A reader of the codebase before
  plan 4 lands sees a flat structure that contradicts
  `ARCHITECTURE.md`. This is confusing without context.
- Plan 4 is a non-trivial refactor with no new user-facing behaviour.
  Easy to deprioritise. If it gets skipped, the codebase grows further
  into a shape that diverges from the spec, making the eventual
  refactor harder.
- A reader who starts with the spec and then opens the source will
  be surprised by the gap. The spec is the *destination*, not the
  current state.

### Mitigations
- `ARCHITECTURE.md`, `CLAUDE.md`, and each plan's preamble all repeat
  that plans 1–3 deliberately defer abstractions and that plan 4 is the
  alignment point. The "What's actually built today" section of
  `ARCHITECTURE.md` calls out the current state versus the destination
  explicitly.
- Plan 4 is sequenced *before* the second provider (plan 5) and the
  second adapter (plan 6). Adding either of those without first
  extracting the seams would be painful, which creates natural pressure
  to do plan 4 rather than skip it.
- Each plan's "what we deliberately did not do" section names the plans
  that follow, so a reader can always trace from the current state to
  the destination.

## Revisit if

- A plan's scope grows past "one concern, mostly TDD-able in a session".
  Split it.
- Plan 4 turns out to be too large in practice (more than ~10 tasks).
  Split into 4a/4b along a natural seam — e.g. extract `AgentEvent`
  and `AgentRuntime` first, `ModelGateway` and `ChatMemoryStore` second.
- The project gains a contributor who needs the abstractions before
  plan 4 lands (e.g. to ship a parallel adapter). Move plan 4 earlier
  in the sequence.
