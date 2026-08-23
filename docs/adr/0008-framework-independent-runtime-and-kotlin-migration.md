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

Quark is now positioned as a small, embeddable, streaming-first agent execution runtime for the JVM. The implementation does not match that destination yet.

What exists today, verified against the source tree:

- a working Telegram -> Gemini walking skeleton extracted in Plan 4 into `core`, `runtime`, `memory`, `provider`, and `adapter.telegram` seams;
- a typed seven-variant `AgentEvent` lifecycle with exactly one terminal event per turn;
- a behavioral test suite covering event order, persistence semantics, memory bounds, streaming render, and cross-request memory;
- a single Gradle module on Java 25 / Quarkus 3.35.4.

Current framework coupling includes:

| Location | Coupling |
| --- | --- |
| `runtime/AgentRuntime.java` | `@ApplicationScoped`, `@Inject`, `io.quarkus.logging.Log`, Mutiny `Multi` |
| `memory/InMemoryChatMemoryStore.java` | `@ApplicationScoped`, `@Inject`, `@ConfigProperty` |
| `provider/*` | `Multi<String>` SPI; Gemini gateway is a CDI bean around Quarkus LangChain4j |
| `adapter/telegram/*` | Quarkus lifecycle events, REST client, Arc request-context handling |
| runtime + memory tests | some wiring/behavior guards require `@QuarkusTest` or CDI request contexts |

Two observations sharpened the decision:

1. **Embeddability is currently false.** The runtime cannot be constructed and exercised as a plain JVM object graph without Quarkus/CDI ownership.
2. **Framework drift is easy to create.** The abandoned Plan 5 provider-selection experiment deepened CDI/config coupling inside orchestration. The experiment was discarded rather than preserved because it was small, incomplete, non-compiling, and would be cheaper to reimplement against the neutral provider boundary if that need returns.

The useful product intent survives: NIM may still become a second provider later. The discarded implementation does not become a compatibility target.

This ADR decides the migration direction and the smallest safe path. It intentionally does not design the target runtime in detail — architecture must earn itself.

---

## 2. Current architecture

```text
Telegram update
      |
      v
adapter.telegram -> TurnRequest
      |
      v
AgentRuntime.execute(TurnRequest) : Multi<AgentEvent>
      |  load history via ChatMemoryStore
      |  build system + history + user prompt
      v
ModelGateway.stream(prompt) : Multi<String>
      |
      v
TokenEmitted* -> persist-on-success -> ModelCompleted -> TurnCompleted | TurnFailed
      |
      v
Telegram renderer
```

Key semantic properties of this pipeline:

- execution is cold: per-turn state lives inside deferred execution;
- failures are represented by `TurnFailed`, then the stream completes normally;
- exactly one terminal event is emitted per turn;
- persistence happens only on successful non-blank completion;
- memory is bounded, session-isolated, and owned by the runtime (ADR 0007).

The streaming primitives are currently SmallRye Mutiny end to end: `Multi<AgentEvent>` at the runtime boundary and `Multi<String>` at the provider boundary.

---

## 3. Problem

1. **Host lock-in.** An `@ApplicationScoped` runtime owned by Arc cannot satisfy the product goal of embedding Quark in Spring Boot, Quarkus, Ktor, Micronaut, plain JVM, CLI, or other hosts.
2. **Type-system lock-in.** `Multi` in public contracts forces SmallRye Mutiny onto every consumer.
3. **Lifecycle lock-in.** CDI annotations, MicroProfile configuration, and Quarkus logging make runtime construction and lifecycle framework-owned.
4. **Streaming/cancellation complexity.** The runtime is fundamentally streaming; its migration needs an execution model with first-class cancellation and structured concurrency.
5. **Unmanaged drift.** Without a neutrality boundary, future features can keep adding host-framework concerns to the runtime core.

Quarkus itself is not the problem. It remains a valid host. LangChain4j behind provider-specific code is also not the problem. The goal is to move runtime semantics out from under host ownership while preserving working behavior.

---

## 4. Decision

### D1 — Kotlin becomes the primary implementation language

New runtime work prefers Kotlin. Java and Kotlin coexist during migration. Existing Java code moves only under migration pressure. No big-bang rewrite.

### D2 — Kotlin/JVM, not Kotlin Native-first

The runtime remains JVM-based. Kotlin Native, GraalVM native image, and other AOT targets are not architectural foundations. JVM interoperability and the existing Java ecosystem remain primary.

### D3 — Framework independence of the core

Public runtime semantics must eventually stop depending on:

- Quarkus bootstrap/lifecycle/config/logging;
- CDI / Arc and Jakarta injection annotations;
- SmallRye Mutiny;
- MicroProfile configuration.

Those dependencies may remain temporarily in host/integration code while migration is incomplete.

### D4 — Kotlin Coroutines / Flow is the semantic successor of Mutiny

The runtime moves toward a contract conceptually like:

```kotlin
interface AgentRuntime {
    fun execute(request: TurnRequest): Flow<AgentEvent>
}
```

This is not a mechanical `Multi -> Flow` rename. The migration must preserve cold execution, typed lifecycle events, cancellation semantics, one terminal runtime outcome, per-turn state isolation, and transport independence. The exact final API may evolve during implementation.

### D5 — Explicit composition instead of mandatory DI

The core runtime must be constructible without a DI container:

```kotlin
val runtime = DefaultAgentRuntime(
    provider = provider,
    memory = memory,
)
```

Spring, Quarkus, Micronaut, Koin, or manual construction may supply dependencies externally. The core owns no DI solution.

### D6 — Quarkus becomes a host/integration

Quarkus is not removed immediately. The migration extracts the runtime from it while preserving the current application.

Target relationship:

```text
Telegram -> Quarkus host/adapter -> Quark Runtime -> provider adapter
```

A future dedicated Quarkus integration may exist, but is not designed here.

### D7 — Incremental, behavior-preserving migration

Every migration increment lands green. Existing tests are behavioral evidence; missing guards are added before moving the semantics they protect.

### D8 — Do not lock the provider contract into `Flow<String>`

The current `ModelGateway.stream(...) : Multi<String>` is text-only. Blindly translating it to `Flow<String>` risks freezing a boundary that may later need tool calls, usage metadata, finish reasons, model metadata, or structured errors.

A structured provider event model is a possible future shape, not a decision in this ADR.

The decision now is only that the provider boundary stays narrow and replaceable, and that widening it must be driven by implementation pressure.

### D9 — One Gradle module for now

The build remains a single Gradle module through the early migration increments. Module splitting is decided only when implementation or publishing pressure justifies it.

---

## 5. Migration strategy

The labels below are sequencing labels, not module designs.

### Phase 0 — Restore a green baseline (completed 2026-08-23)

The unfinished Plan 5 NIM/provider-preference experiment was removed from the active tree and intentionally abandoned. The pre-Plan-5 runtime was restored and verified green with `./gradlew test`, `./gradlew spotlessCheck`, and `./gradlew build`.

Phase 0 is restoration, not architecture. NIM remains only a future candidate second provider; if needed, it should be implemented fresh against the neutral provider boundary rather than porting the discarded CDI-centric design.

### Migration 1 — Kotlin and Coroutines build support

Add Kotlin alongside Java, add the Kotlin JVM Gradle plugin and `kotlinx-coroutines-core`, and prove Java/Kotlin interoperability with test-tree-only code. No production code migrates and no runtime behavior changes.

Detailed plan: [`docs/superpowers/plans/2026-08-23-migration-1-kotlin-coroutines-build-support.md`](../superpowers/plans/2026-08-23-migration-1-kotlin-coroutines-build-support.md).

### Migration 2 — Lock streaming and cancellation semantics

Before any `Multi -> Flow` port, close the two migration-critical behavioral gaps:

- **G-1:** prove cold-stream / multi-subscription isolation, including overlapping subscriptions and distinct executions/turnIds;
- **G-2:** define and test cancellation separately from runtime failure. In Kotlin, coroutine cancellation must not be accidentally converted into `TurnFailed`.

This increment adds evidence; it does not change existing runtime behavior.

### Migration 3 — Framework-neutral Kotlin runtime contracts

Introduce runtime-facing contracts in Kotlin without Quarkus, Mutiny, or Jakarta types. Exact API shape is still implementation-driven. Do not introduce a provider event hierarchy until a real need earns it.

### Migration 4 — Migrate the runtime implementation

Port `AgentRuntime` behavior to the neutral core while preserving protected invariants and their tests. The Quarkus application temporarily adapts the new runtime back into the host while migration is incomplete.

### Migration 5 — Remove framework ownership from the core

The core runtime becomes constructible and testable without Quarkus/CDI. Residual Quarkus/Mutiny usage survives only in host/adapter code.

### Later — Optional host integrations

Only after the neutral runtime milestone is real should dedicated Quarkus, Spring, Ktor, or Micronaut integrations be considered.

---

## 6. Behavioral invariants to protect

These are current runtime semantics, not abandoned Plan 5 behavior.

| # | Invariant | Current evidence |
| --- | --- | --- |
| I-1 | Happy-path lifecycle preserves `TurnStarted -> MemoryLoaded -> ModelInvoked -> TokenEmitted* -> ModelCompleted -> TurnCompleted` ordering | `AgentRuntimeTest` happy-path sequence |
| I-2 | Events in one turn share a non-blank `turnId`; distinct executions receive distinct turnIds | `AgentRuntimeTest` |
| I-3 | Gateway receives system prompt + history + pending user message | `AgentRuntimeTest` |
| I-4 | Successful turn persists USER then ASSISTANT, in order | `AgentRuntimeTest` |
| I-5 | Blank/whitespace-only completion persists nothing while still completing the turn | `AgentRuntimeTest` + Telegram renderer fallback tests |
| I-6 | Runtime failures produce exactly one terminal `TurnFailed`, normal stream completion, and no partial turn persistence | `AgentRuntimeTest` failure paths |
| I-7 | Execution is cold and per-turn mutable state is not shared across independent subscriptions | sequential evidence exists; overlapping proof is G-1 |
| I-8 | Memory remains bounded, snapshot-safe, session-isolated, and reset targets one session | `InMemoryChatMemoryStoreTest` |
| I-9 | Conversation memory survives the real request-context boundary and reset drops history | Telegram conversation/streaming/reset tests |
| I-10 | Telegram rendering preserves throttling, edit/clamp behavior, blank/failure fallback, and typed event projection | Telegram stream/throttle/messages/commands tests |
| I-11 | Provider SDK behavior remains isolated behind the provider boundary; provider stream errors are translated by the runtime rather than provider code emitting `AgentEvent` | `GeminiModelGatewayTest` + runtime failure tests |

### Known gaps

- **G-1 — overlapping cold-stream behavior.** Sequential re-runs exist; overlapping subscriptions are not yet explicitly pinned.
- **G-2 — cancellation/timeout path.** The Telegram stream timeout/cancel branch lacks a dedicated behavioral guard and the future coroutine cancellation mapping is not yet defined in code.
- **G-3 — neutrality enforcement.** Historical ArchUnit enforcement never landed. Migration 3 should mechanically prevent the new neutral core from importing Quarkus/Mutiny/Jakarta/MicroProfile types.
- **G-4 — live CHAT shell altitude.** The poller `handle()` streaming path is not directly tested at the same altitude as the lower dispatch seam.

Abandoned Plan 5 provider-selection behavior is deliberately absent from this invariant list. If provider selection is reintroduced later, it must earn a new contract and new tests against the neutral architecture.

---

## 7. Consequences

### Positive

- the embeddability claim becomes implementable;
- consumers no longer need Mutiny as part of the eventual runtime contract;
- structured cancellation and coroutine tooling fit the streaming-first execution model;
- Quarkus stops being load-bearing for runtime semantics;
- new framework coupling can be fenced mechanically.

### Negative

- Java and Kotlin coexist temporarily;
- the Quarkus host needs an adaptation boundary during migration;
- Kotlin Gradle plugin / Gradle / Java 25 compatibility must be verified in Migration 1;
- contributors must be comfortable reviewing Kotlin.

---

## 8. Alternatives considered

- **Stay on a Quarkus-owned runtime:** rejected because it contradicts the product direction.
- **Keep Mutiny as the public API:** rejected because it preserves the coupling being removed.
- **Java `Flow` / manual Publisher as the primary path:** rejected because it would require rebuilding much of the streaming/cancellation ergonomics already provided by coroutines.
- **Project Reactor as the neutral type:** viable, but would substitute another external reactive type for the Kotlin-primary execution model.
- **Big-bang Kotlin rewrite:** rejected because it discards the working behavioral baseline.
- **Adopt a DI container for the core:** rejected; composition belongs to the host.
- **Kotlin Multiplatform / Native-first:** rejected because there is no demonstrated non-JVM requirement.
- **Carry Plan 5 forward:** rejected. Its incomplete CDI-centric implementation would create rework; NIM can be rebuilt later if it still matters.

---

## 9. What remains intentionally unresolved

Decide during implementation, when pressure exists:

- exact provider event model and when text-only streaming needs to widen;
- exact Kotlin `AgentRuntime` API shape;
- final Gradle/module topology and publishing boundaries;
- Java-facing ergonomics for Flow-based APIs;
- the temporary Quarkus host adaptation strategy;
- neutral-core logging and configuration approach;
- whether provider preference/selection should exist at all, and if so where it belongs;
- dedicated host integrations;
- plugin systems, policy DSLs, approvals, Temporal/durable execution, cloud/control-plane, fleet management, multi-agent orchestration, replay/checkpointing, generic Spring AI/LangChain4j integrations, and additional providers.

---

## 10. Relationship to prior ADRs

- **ADR 0001:** not superseded. Typed lifecycle events, failures-as-events, terminal-event semantics, and transport projection carry forward. Only the stream carrier changes directionally from Mutiny to Flow.
- **ADR 0002:** superseded in part. Quark will not remain a single Quarkus-owned application runtime. Its anti-premature-modularization pragmatics carry forward: one Gradle module for now, package-level boundaries, and eventual mechanical boundary enforcement.
- **ADR 0003:** method carried forward; roadmap overtaken. Walking-skeleton-first incremental extraction remains the migration method. Plans 5–7 are historical context, not the active roadmap.
- **ADR 0004:** unaffected.
- **ADR 0005:** unaffected.
- **ADR 0006:** unaffected and remains historical context for the AI-service/CDI memory-scope trap.
- **ADR 0007:** unaffected in substance. Runtime-owned memory, persist-on-success-only, blank-skip, and failure semantics remain protected.

---

## 11. Acceptance criteria — framework-independent runtime milestone

Migration 5 is complete when all of the following hold:

1. A `DefaultAgentRuntime` (or equivalent) is constructible in plain Kotlin/JVM using constructor arguments only — no container and no framework annotations.
2. A plain JVM test, without `@QuarkusTest`, CDI, or Quarkus boot, runs a full turn against a fake provider and in-process memory and collects the event stream.
3. The neutral core source set has zero imports of Quarkus, Mutiny, Jakarta injection/enterprise, MicroProfile config, or Quarkus logging, enforced mechanically.
4. Every current invariant in §6 has a passing guard at equivalent altitude, including G-1/G-2 closure where the moved code depends on them.
5. The existing Quarkus Telegram application still works over the extracted runtime and the cross-request memory guards remain green.
6. CI remains green; any intentional semantic deviation ships with an explicit decision before the behavior-changing code.
