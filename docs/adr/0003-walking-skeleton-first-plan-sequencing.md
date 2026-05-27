# 0003 — Walking-skeleton-first plan sequencing

**Status:** Accepted, 2026-05-25. This ADR fixes the *sequencing strategy*; individual plan files do not yet exist and are authored one at a time, immediately before each plan starts.
**Deciders:** Daniel + brainstorming session.

**Related documents:**
- [`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](../superpowers/specs/2026-05-25-agent-runtime-mvp.md) — MVP design.
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — Bootstrap / MVP target / Destination split + Bridge table.
- [`docs/superpowers/plans/`](../superpowers/plans/) — implementation-plan artefacts. Currently empty; the first plan to author is Plan 1 below.

---

## Status note

The decision recorded here is the *order* in which architectural
expansion happens, not a commitment to write seven plan documents
up-front. Writing all seven plans now would repeat the mistake this
ADR exists to prevent: designing against speculation rather than
working code. Each plan is authored only when the previous one has
landed and there is real code to plan against.

What the table below fixes is the **shape** of each increment and the
**point** at which each abstraction is allowed to appear. The plan
files materialise as the work approaches them.

---

## Context

The destination runtime — [`AgentRuntime`](0001-event-driven-agentevent-stream.md),
`AgentEvent`, `ModelGateway`, `ChatMemoryStore`, `ProviderPreferenceStore`,
two transport adapters, two model providers, ArchUnit-enforced
boundaries, Micrometer observability — is a substantial system to build
end-to-end in one pass.

Two plausible build strategies exist:

1. **Top-down.** Implement the abstractions first — `AgentEvent`,
   `AgentRuntime`, the SPIs — with stub implementations behind them,
   then fill in real providers and adapters.

2. **Bottom-up walking skeleton.** Build the smallest end-to-end thing
   that works — Telegram polling + one model, no abstractions, no
   memory, no streaming runtime — then *refactor* it toward the
   destination across several plans, introducing each abstraction only
   when there is enough real code to shape it correctly.

Top-down is the natural reflex when a strong destination design
already exists. It also has a known failure mode: abstractions get
designed against the document rather than against running code, and
the first non-trivial requirement that does not fit the abstraction
either forces a workaround or triggers a rewrite of the supposedly
foundational layer.

Walking skeleton is the deliberate antidote:

- build the smallest working path first,
- validate behaviour end-to-end,
- extract abstractions from working code instead of inventing them
  upfront.

The project owner explicitly requested:

> "tiny, with few files and basic, without much architecture"
> for the first implementation phase, then gradual refactoring and
> complexity growth afterward.

This ADR formalizes that sequencing.

---

## Decision

`quark` is built through a sequence of incremental plans. Each plan
introduces exactly one meaningful architectural expansion. Plan files
are written one at a time into [`docs/superpowers/plans/`](../superpowers/plans/)
as each plan begins — not all up-front.

The fixed sequence:

| Plan | Increment                                                              | Abstractions introduced                                    |
|------|------------------------------------------------------------------------|------------------------------------------------------------|
| 1    | Telegram polling + Gemini, single message in / single message out      | None. Direct `@RegisterAiService` injection.               |
| 2    | In-process working memory + `/reset` command                           | None. Memory stored on the dispatcher.                     |
| 3    | Telegram streaming via throttled message edits                         | None new. Streaming remains Telegram-specific.             |
| 4    | Extract `AgentRuntime`, `AgentEvent`, `ModelGateway`, `ChatMemoryStore` | Core runtime abstractions introduced.                      |
| 5    | NIM provider + `/provider` + `/status`                                 | `ProviderPreferenceStore` + second gateway implementation. |
| 6    | REST + SSE adapter                                                     | Second transport validates the event-stream architecture.  |
| 7    | ArchUnit boundaries + Micrometer observability                         | Structural enforcement and operational visibility.         |

Plan 4 is the architectural inflection point.

By the time it lands, Plans 1–3 have produced:

- real Telegram behaviour,
- real streaming,
- real provider interaction,
- real memory usage,
- and real operational constraints.

The abstractions extracted in Plan 4 are therefore shaped by working
code rather than by speculation.

---

## Consequences

### Positive

- Every plan produces runnable software that can be validated
  end-to-end.
- Architectural seams are extracted from real duplication and real
  pressure instead of hypothetical future needs.
- The early codebase stays small and understandable during the
  exploratory phase.
- The project can demonstrate visible progress extremely early.
- Refactors happen while the codebase is still small enough to reshape
  safely.

### Negative

- Plans 1–3 intentionally diverge from the destination architecture in
  [ADR 0001](0001-event-driven-agentevent-stream.md) and
  [ADR 0002](0002-single-quarkus-module-archunit-boundaries.md).
- The codebase temporarily contains "wrong-looking" structure by
  design.
- Plan 4 is mostly refactoring with little user-visible functionality,
  which makes it psychologically easy to postpone.
- A contributor reading the ADRs before reading `ARCHITECTURE.md` may
  expect abstractions that do not yet exist in the source tree.

### Mitigations

- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) explicitly separates the
  **Today** section (MVP) from the **Destination** section
  (event-driven runtime), with the Bridge table summarising this ADR.
- Each ADR that describes destination architecture (0001, 0002) opens
  with a Status note pointing back to this sequencing.
- Plans are ordered so that adding a second provider or second adapter
  becomes painful without first extracting the runtime seams. This
  creates natural pressure to do the refactor at the correct moment
  instead of postponing it indefinitely.
- Each plan, when authored, must document what exists, what is
  intentionally deferred, and what future plans will introduce.
- Until a plan file exists, [`docs/progress.md`](../progress.md)
  records which plan number is next.

---

## Why this matters

The central belief:

> architecture should emerge from validated execution paths, not from
> speculative layering.

The destination is still important. The ADRs and `ARCHITECTURE.md`
still define it. But the implementation path deliberately prioritises:

1. feedback,
2. operational learning,
3. runtime validation,
4. incremental extraction of abstractions.

The project optimises for learning the correct runtime shape, not for
appearing architecturally complete on day one.

---

## Revisit if

- A plan grows beyond "one concern, implementable in a focused session."
- Plan 4 becomes too large and needs splitting into smaller refactors.
- Another contributor requires stable abstractions earlier than planned.
- The walking skeleton accumulates too much technical debt before the
  extraction phase lands.
