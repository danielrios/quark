# 0008 — Framework-independent runtime and the Kotlin/JVM migration

**Status:** Accepted, 2026-08-23.
**Deciders:** Daniel (project owner) + architecture session.
**Supersedes (in part):** [ADR 0002](0002-single-quarkus-module-archunit-boundaries.md) — see §10.

**Related documents:**

- [`README.md`](../../README.md) / [`MANIFESTO.md`](../../MANIFESTO.md) — "Framework is not runtime".
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — current technical truth and migration direction.
- [`docs/vision/runtime-platform.md`](../vision/runtime-platform.md) — product thesis this migration serves.
- [ADR 0001](0001-event-driven-agentevent-stream.md) — the event-stream contract carried forward.
- [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md) — incremental-extraction method reused here.
- [ADR 0007](0007-agent-runtime-owns-conversation-memory.md) — behavioral deviations that become protected invariants (§6).

---

## 1. Context

The repository repositioning is complete: quark is now described as *"a small,
embeddable, streaming-first agent execution runtime for the JVM"*, and the
manifesto states plainly that **framework ownership of the runtime is not
acceptable**. The implementation does not match that claim yet.

What actually exists today (verified against the source tree, not the docs):

- a working Telegram -> Gemini walking skeleton, extracted in Plan 4 into the
  destination seams: `core` (sealed `AgentEvent`, `TurnRequest`,
  `ChatMessage`), `runtime.AgentRuntime`, `memory.ChatMemoryStore` +
  in-memory impl, `provider.ModelGateway` + `provider.gemini`, and
  `adapter.telegram`;
- a typed seven-variant event lifecycle with exactly one terminal event per turn;
- a behavioral test suite (~50 tests) covering event order, persistence
  semantics, memory bounds, streaming render, and cross-request memory;
- a single Gradle module on Java 25 / Quarkus 3.35.4.

Where the code is coupled to the framework (verified):

| Location | Coupling |
| --- | --- |
| `runtime/AgentRuntime.java` | `@ApplicationScoped`, `@Inject` constructor, `io.quarkus.logging.Log`, `Multi` pipeline; (the archived provider-selection experiment had begun adding CDI `@Any Instance<ModelGateway>` + `@Named` selection and `@ConfigProperty`) |
| `memory/InMemoryChatMemoryStore.java` | `@ApplicationScoped`, `@Inject`, `@ConfigProperty` |
| `provider/*` | `Multi<String>` SPI; gateways are `@ApplicationScoped` CDI beans wrapping quarkus-langchain4j model beans |
| `adapter/telegram/*` | `@Observes StartupEvent/ShutdownEvent`, MicroProfile REST client, Arc request-context activate/terminate per update |
| runtime + memory tests | `RuntimeWiringTest` requires `@QuarkusTest`; the memory guards drive real CDI request contexts |

Two observations sharpened the decision:

1. **Embeddability is currently false.** The runtime cannot be constructed or
   exercised without a CDI container and a Quarkus boot — even a wiring smoke
   test needs `@QuarkusTest`.
2. **Boundary drift is measurable.** The first post-Plan-4 feature increment
   (a provider-selection experiment, preserved on `archive/plan-5-nim-provider-wip`)
   reached *deeper* into CDI (`Instance`, `@Named`, `@ConfigProperty`) inside
   the orchestration core. Each feature added on top of the framework-owned
   runtime makes the eventual extraction more expensive. The drift direction,
   not any single line, is the problem.

This ADR decides the migration direction and the smallest safe plan for it.
It intentionally does **not** design the target runtime in detail —
architecture must earn itself.

---

## 2. Current architecture

```text
Telegram update (virtual-thread poll loop, @Observes StartupEvent)
      |  Arc request context activated per update
      v
adapter.telegram  -- TurnRequest(sessionId, provider?, message)
      |
      v
AgentRuntime.execute(TurnRequest) : Multi<AgentEvent>   <- @ApplicationScoped, CDI-injected
      |  loads history via ChatMemoryStore (SPI, @ApplicationScoped impl)
      |  builds system + history + user prompt
      v
ModelGateway.stream(prompt) : Multi<String>             <- langchain4j confined to provider.*
      |
      v
TokenEmitted* -> persist-on-success -> ModelCompleted -> TurnCompleted | TurnFailed
      |
      v
Telegram renderer (throttled edits, instanceof projection)
```

Key semantic properties of this pipeline (all pinned by tests — §6):

- `execute` returns a **cold** stream; per-turn state (turnId, accumulator)
  lives inside the deferred supplier;
- failures are **events** (`TurnFailed`), never `onError()`; exactly one
  terminal event, then normal completion;
- persistence happens **only** on successful non-blank completion;
- memory is bounded, session-isolated, and owned by the runtime (ADR 0007).

The streaming primitives are SmallRye Mutiny end to end:
`Multi<AgentEvent>` at the runtime boundary, `Multi<String>` at the
provider boundary.

---

## 3. Problem

1. **Host lock-in.** The stated product — a runtime embeddable by Spring
   Boot, Quarkus, Ktor, Micronaut, plain JVM, CLI hosts — cannot be delivered
   by an `@ApplicationScoped` bean that only exists inside an Arc container.
   Today Quarkus owns the lifecycle; the thesis requires the reverse.
2. **Type-system lock-in.** `Multi` in the public contracts forces SmallRye
   Mutiny onto every consumer, including future hosts without Quarkus on the
   classpath. The same applies to Jakarta injection annotations and Quarkus
   config/logging inside runtime classes.
3. **Language ceiling.** A streaming-first runtime lives on cancellation,
   structured concurrency, and suspension. Kotlin coroutines model these
   natively and remain fully JVM-interoperable, letting hosts keep their own
   language and stack. Java alone offers no comparable first-class streaming
   and cancellation story today.
4. **Unmanaged drift.** Without an enforced neutrality boundary, every
   increment adds framework surface to the core (see §1). The cost of
   extraction grows monotonically.

Not problems (deliberately left alone): Quarkus itself — it is a fine *host*;
the current behavior — it works and will be preserved; langchain4j — fine
behind the provider boundary; the walking-skeleton investment — it is the
asset this migration protects.

---

## 4. Decision

### D1 — Kotlin becomes the primary implementation language

New runtime work prefers Kotlin. Java and Kotlin coexist during the migration;
existing Java code moves only under migration pressure. **No big-bang
rewrite**: the walking skeleton and its tests are treated as behavioral
specification, not legacy to discard.

### D2 — Kotlin/JVM, not Kotlin Native

The runtime stays JVM-only. Kotlin Native, GraalVM native-image, and similar
ahead-of-time targets are **not** architectural foundations. JVM
interoperability, dynamic loading, mature tooling, and compatibility with the
existing Java ecosystem outweigh native footprints. (The current Quarkus
native Dockerfiles remain a property of the host application; they impose
nothing on the core runtime.)

### D3 — Framework independence of the core

Public runtime semantics must eventually stop depending on:

- Quarkus (bootstrap, config, logging, lifecycle events),
- CDI / Arc (`@ApplicationScoped`, `@Inject`, `Instance`),
- Jakarta injection/lifecycle annotations,
- SmallRye Mutiny,
- MicroProfile configuration,
- quarkus-specific logging.

These dependencies may remain **temporarily** in host/integration code — the
Telegram adapter, the Quarkus wiring, provider glue — while the migration is
incomplete. The core's *public semantics* are what must go neutral.

### D4 — Kotlin Coroutines / Flow is the semantic successor of Mutiny

The runtime streaming contract moves toward:

```kotlin
interface AgentRuntime {
    fun execute(request: TurnRequest): Flow<AgentEvent>
}
```

This is **not** a mechanical `Multi` -> `Flow` rename. The migration must
preserve the current streaming semantics (§6): cold execution, typed lifecycle
events, cancellation behavior, exactly one terminal outcome, no shared
per-turn mutable state, transport independence. The exact final API shape may
evolve during implementation; the *direction* is decided now.

### D5 — Explicit composition instead of mandatory DI

The core runtime must be constructible without any DI container:

```kotlin
val runtime = DefaultAgentRuntime(
    provider = provider,
    memory = memory,
)
```

Spring, Quarkus, Micronaut, Koin, or plain manual construction may supply
dependencies externally. The core owns **no** DI solution — not CDI, not a
replacement container. Configuration enters through constructor parameters,
not annotation injection.

### D6 — Quarkus becomes a host/integration

Quarkus is not removed. The current application keeps working throughout the
migration; the runtime is extracted *from* it. Target relationship:

```text
Telegram -> Quarkus host/adapter -> Quark Runtime -> provider adapter
```

rather than `Quark Runtime -> Quarkus`. A dedicated `quark-quarkus`
integration module may exist someday; it is explicitly **not designed here**.

### D7 — Incremental, behavior-preserving migration

Each phase lands green (§5). The existing test suite is the behavioral
evidence; where it is insufficient to protect an invariant, the gap is named
(§6) and closed *before* the corresponding code moves.

### D8 — Provider contract: do not lock in `Flow<String>`

The current `ModelGateway.stream(...) : Multi<String>` streams only text
chunks. Blindly translating it to `Flow<String>` would freeze a boundary the
new runtime thesis will likely outgrow: tool calls, provider usage, finish
reasons, model metadata, and structured provider errors do not fit
`String`. Conceptually:

```kotlin
sealed interface ModelEvent {
    data class Token(val text: String) : ModelEvent
    data class ToolCall(/* ... */) : ModelEvent
    data class Usage(/* ... */) : ModelEvent
    data class Completed(/* ... */) : ModelEvent
}
```

**Decision now:** only that the provider boundary must stay *narrow but
replaceable* — a future Kotlin gateway may begin text-chunk-shaped, provided
widening to a structured `ModelEvent` stream stays additive and does not leak
into the runtime/event contract prematurely. **Nothing from the sketch above
is implemented in this step.** The full model is decided when implementation
pressure (tool-call or usage metadata) demands it — abstractions must earn
themselves. See §9.

### D9 — One Gradle module for now

The build stays a single module through Phases 1–3. Splitting
(`quark-core` / `quark-runtime` / hosts) is decided under implementation
pressure (§9), not pre-declared. This preserves ADR 0002's *pragmatics* even
as its Quarkus premise is superseded (§10).

---

## 5. Migration strategy

Sequenced increments; every increment ends with a green build and unchanged
observable behavior unless an explicit, separately-recorded decision says
otherwise. The headings below are sequencing labels, not module designs.

### Phase 0 — Restore a green baseline (precondition; completed 2026-08-23)

The unfinished Plan 5 experiment (NIM provider + provider preference) predates
this ADR. It was removed from the active baseline without being destroyed:
preserved as a WIP snapshot on branch `archive/plan-5-nim-provider-wip`
(including archive notes documenting its known-broken state), the runtime
restored to its pre-Plan-5 state, and the gates re-run green (`./gradlew test`,
`spotlessCheck`, `build`). Phase 0 is restoration, not architecture — it adds
nothing to the target design. NIM remains a candidate second provider once the
neutral provider boundary exists.

### Migration 1 — Kotlin and Coroutines build support (first engineering PR)

Add Kotlin alongside Java: Kotlin JVM Gradle plugin, Kotlin stdlib,
`kotlinx-coroutines-core` (required by the framework-neutral contracts work).
Prove Java/Kotlin interoperability with a minimal test-tree-only smoke test.
No production code migrates. No runtime behavior changes. Scope detailed in
[`docs/superpowers/plans/2026-08-23-migration-1-kotlin-coroutines-build-support.md`](../superpowers/plans/2026-08-23-migration-1-kotlin-coroutines-build-support.md).

Dependencies are added only when the next step needs them: no
`kotlinx-serialization`, no `quarkus-kotlin`, no test libraries beyond what
the first test needing them justifies.

### Migration 2 — Lock streaming and cancellation semantics

Before any `Multi` -> `Flow` porting, close the behavioral gaps the port
depends on (§6): G-1 — prove cold-stream/multi-subscription isolation
(independent executions and independent `turnId`s); G-2 — pin how cancellation
differs from failure: cancellation must remain distinguishable from a terminal
`TurnFailed`; in Kotlin terms, `CancellationException` must propagate rather
than be swallowed into a failure event. This increment adds evidence and pins
semantics; it changes no existing behavior.

### Migration 3 — Framework-neutral Kotlin runtime contracts

Introduce (or move) the runtime-facing contracts in Kotlin, free of Quarkus,
Mutiny, and Jakarta types: `AgentRuntime`, `AgentEvent`, `TurnRequest`,
`ChatMessage`, the memory SPI, the provider SPI. The existing Java contracts
remain until the runtime cutover; duplication is resolved in Migration 4.

### Migration 4 — Migrate the runtime implementation

Port `AgentRuntime` behavior to the neutral core (e.g. `DefaultAgentRuntime`),
preserving §6 invariants and porting the guarding tests. The Quarkus
application adapts the new runtime back into the host (CDI producers may
construct the runtime and bridge `Flow` toward the transport-facing world)
while the migration is incomplete.

### Migration 5 — Remove framework ownership from the core

The core runtime is instantiable and testable without Quarkus: the acceptance
criteria in §11 define the milestone. Residual Quarkus/Mutiny usage survives
only in host/adapter code.

### Later — Optional host integrations (not designed now)

Only after §11 is real: `quark-quarkus`, Spring, Ktor, Micronaut
integrations are considered — each with its own ADR-grade justification.

---

## 6. Behavioral invariants (protected)

Each invariant cites its current guarding test. During Migrations 3–5 every
invariant must keep a named guard at equivalent altitude.

| # | Invariant | Guarded today by |
| --- | --- | --- |
| I-1 | Event order `TurnStarted -> MemoryLoaded -> ModelInvoked -> TokenEmitted* -> ModelCompleted -> TurnCompleted/TurnFailed`; 7-event happy path | `AgentRuntimeTest.happyPathEmitsFullEventSequence` |
| I-2 | Every event carries the same non-blank `turnId`; distinct across turns | `AgentRuntimeTest.everyEventCarriesTheSameNonBlankTurnId` |
| I-3 | Gateway receives system prompt + history + pending user message | `AgentRuntimeTest.gatewayReceivesSystemPlusHistoryPlusUserMessage` |
| I-4 | Successful turn persists USER then ASSISTANT — in that order, only then | `AgentRuntimeTest.successfulTurnPersistsUserThenAssistant` |
| I-5 | Blank (zero-token or whitespace-only) completion emits `ModelCompleted`+`TurnCompleted` but persists nothing (ADR 0007); renderer uses the same predicate | `blankCompletionEmits…`, `whitespaceOnlyCompletionPersistsNothing`; `TelegramStreamHandlerTest.*Fallback` |
| I-6 | Any failure — store load, gateway stream, persist — yields exactly **one** terminal `TurnFailed`, normal completion afterward, nothing persisted; `TurnFailed.reason` is message-only, no exception class names | `gatewayFailureEmits…`, `storeLoadFailure…`, `appendFailure…` |
| I-7 | Cold execution: each subscription re-runs the turn; no shared per-turn state | sequential proof in `everyEventCarries…`; overlap untested — gap G-1 |
| I-8 | Memory: bounded eviction, immutable snapshots, per-session isolation, `reset` deletes only the target session | `InMemoryChatMemoryStoreTest` (6 tests) |
| I-9 | Cross-request memory through the real CDI request-context boundary; `/reset` mid-conversation drops history | `TelegramConversationMemoryTest`, `TelegramStreamingMemoryTest`, `TelegramBotRunnerResetTest` |
| I-10 | Transport projection: `instanceof`-based rendering, throttled batched edits, 4096 clamp, `ERR_FALLBACK` on failed/blank turns, placeholder flow | `TelegramStreamHandlerTest`, `TelegramThrottleTest`, `TelegramMessagesTest`, `TelegramCommandsTest` |
| I-11 | Gateways never emit events; stream errors surface as stream failures at the SPI; SDK mapping isolated in provider packages | `GeminiModelGatewayTest` (+ `NimModelGatewayTest`) |
| I-12 | Unknown provider name => `TurnStarted` + `TurnFailed("unknown provider: …")`, no auto-fallback; resolution chain session-preference > request > default; `/reset` does not clear preference *(in-flight work)* | new `AgentRuntimeTest` provider-resolution tests |
| I-13 | Downstream cancellation detaches but does not abort the in-flight model call; late emissions dropped (documented gateway semantics) | javadoc-documented — gap G-2 |

### Known test gaps to close before relying on them

- **G-1 — concurrency/cold-stream altitude.** Only *sequential* re-runs are
  proven; no test drives two overlapping subscriptions of one `execute()`
  stream. Needed before Migration 4 deletes the Mutiny implementation.
- **G-2 — cancellation/timeout path.** `TelegramStreamHandler`'s 60 s latch
  timeout -> `subscription.cancel()` branch is untested.
- **G-3 — no enforced neutrality/boundary checks.** ArchUnit (historical
  Plan 7) never landed; ADR 0002's enforcement premise is currently inert.
  Migration 3 should add a lightweight import-neutrality check for the new
  core (a reflection-based test suffices — no framework needed to police
  frameworks).
- **G-4 — poller/webhook shell.** `TelegramBotRunner.handle()` (placeholder +
  streaming branch) is exercised only via the `dispatch()` seam; the live
  CHAT branch has no direct test.

---

## 7. Consequences

### Positive

- **The embeddability claim becomes implementable** — hosts integrate by
  constructing an object, not by adopting a container.
- **Consumers stop importing Mutiny**; the streaming contract rides Kotlin
  coroutines, consumable from any JVM stack.
- **Better streaming ergonomics** — structured cancellation and
  deterministic coroutine testing fit a streaming-first runtime.
- **Quarkus stops being load-bearing** for semantics; it becomes one host
  among several, protecting the project from single-framework gravity.
- **Drift gets a fence** (D3 + G-3 enforcement) instead of accumulating.

### Negative

- **Two languages, temporarily** — mixed idiom, duplicated test fakes, and a
  real interoperability surface to review.
- **Bridging cost during coexistence** — the Quarkus host adapts `Flow`-based
  runtime output into Mutiny/transport code (hand-written bridge; no
  maintained Mutiny<->Flow converter exists).
- **Toolchain risk** — Kotlin Gradle plugin vs Gradle 9.5.1 vs Java 25
  (`jvmTarget` ceiling) must be verified in Migration 1; mixed bytecode targets
  are the documented fallback.
- **Native-image footprint** — the Kotlin stdlib enlarges the host's native
  build (accepted; D2 explicitly declines to optimize for it).
- **Review competence** — contributors must read Kotlin; mitigated by keeping
  the core small.

---

## 8. Alternatives considered

- **Stay on the Quarkus-owned runtime** (do nothing). Rejected: directly
  contradicts the merged positioning; the drift cost compounds with every
  feature (§1).
- **Java + JDK streaming** (`java.util.concurrent.Flow` / manual
  `Publisher`). Rejected as the primary path: JDK primitives carry no
  operators, cancellation is manual, and the language still lacks structured
  concurrency; we would rebuild Mutiny badly. Remains available later as a
  Java-facing adapter shape.
- **Project Reactor as the neutral stream type.** Viable and battle-tested,
  but it swaps one framework-owned paradigm for another and fights the
  Kotlin-primary decision; `Flow` is native to the chosen language and
  consumption-friendly from Java via small adapters.
- **Keep Mutiny as the public API.** Rejected: permanently couples every
  consumer to SmallRye — the exact lock-in this ADR removes.
- **Big-bang Kotlin rewrite.** Rejected: discards the walking skeleton's
  accumulated behavioral specification and repeats the top-down mistake ADR
  0003 exists to prevent.
- **Adopt a DI container for the core** (Koin/Dagger). Rejected: D5 — plain
  construction; containers belong to hosts.
- **Kotlin Multiplatform.** Rejected: no demonstrated need beyond the JVM;
  premature by years.

---

## 9. What remains intentionally unresolved

Decided **during implementation**, when pressure exists — not now:

- the exact provider event model (`ModelEvent` variants, usage/tool-call/
  finish-reason payloads) and the moment `Flow<String>` widens;
- the exact Kotlin `AgentRuntime` interface shape (naming, suspension points,
  error channel);
- final Gradle/module topology (whether api/runtime/providers/adapters become
  separate modules, and when artifacts get published);
- Java-facing ergonomics of the Flow contracts, and the Mutiny<->Flow bridge
  the Quarkus host uses during coexistence;
- the core's logging and configuration approach (constructor-injected values
  vs a facade vs slf4j-api);
- whether the in-flight provider-preference semantics survive unchanged once
  they meet a neutral core;
- all host-integration designs (`quark-quarkus`, Spring, Ktor, Micronaut);
- everything on the standing deferral list: plugin systems (PF4J), policy
  DSLs, approval services, Temporal/durable execution, cloud/control-plane,
  fleet management, multi-agent orchestration, replay/checkpointing beyond
  behavior preservation, generic LangChain4j/Spring AI integrations, further
  providers.

---

## 10. Relationship to prior ADRs

- **ADR 0001 (event-driven `AgentEvent` stream)** — **not superseded.** Its
  substance — typed lifecycle events, failures-as-events, exactly one
  terminal event, transport projection — carries forward intact. Only the
  *type carrier* of the stream (`Multi` -> `Flow`) changes, recorded as a
  refinement under D4.
- **ADR 0002 (single Quarkus module)** — **superseded in part.** The premise
  that quark remains a single *Quarkus application* module is retired:
  Quarkus will become an optional host, and the runtime will exist
  independently of it. Carried forward: the anti-premature-modularization
  pragmatics (one Gradle module for now, D9), package-level boundaries, and
  the intent to enforce boundaries by test — reframed as the neutrality
  check (G-3).
- **ADR 0003 (walking-skeleton-first sequencing)** — **method carried
  forward, roadmap table overtaken.** Incremental extraction from working
  code is exactly how §5 proceeds. Its historical Plans 5–7 remain design
  history; new increments follow §5 and are re-evaluated rather than
  executed mechanically.
- **ADR 0004 (harness)** — unaffected. Non-dev-mode Gradle gates
  (`./gradlew test`, `spotlessCheck`) continue to apply; Kotlin additions
  ride the same gates.
- **ADR 0005 (release automation)** — unaffected.
- **ADR 0006 (AI-service memory scope)** — unaffected; historical. Its
  Context section stays authoritative for the `@RegisterAiService` scope
  trap.
- **ADR 0007 (runtime owns memory)** — unaffected in substance; its
  deliberate deviations (persist-on-success-only, blank-skip, message-only
  failure reasons) are protected as I-4/I-5/I-6 and must survive the port.

---

## 11. Acceptance criteria — framework-independent runtime milestone

Migration 5 is done when **all** of the following hold:

1. A `DefaultAgentRuntime` (or equivalent) is constructible in plain
   Kotlin/JVM with constructor arguments only — no container, no annotations.
2. A plain test (no `@QuarkusTest`, no CDI, no Quarkus boot) runs a full turn
   end-to-end against fake provider and in-process memory:

   ```kotlin
   val events = runtime.execute(request).toList()
   ```

3. The neutral core source set compiles with **zero** imports of Quarkus,
   Mutiny, `jakarta.inject`/`jakarta.enterprise`, MicroProfile config, or
   quarkus logging — enforced mechanically (G-3), not by convention.
4. Every invariant in §6 has a named, passing guard at equivalent altitude,
   including closures for G-1 and G-2 where the moved code depends on them.
5. The existing Quarkus application serves Telegram end-to-end over the
   extracted runtime (host wires it in), and the cross-request memory guards
   (I-9) stay green unchanged.
6. CI (spotless + tests) is green throughout; any deviation from §6 behavior
   ships with its own ADR before the code that changes it.
