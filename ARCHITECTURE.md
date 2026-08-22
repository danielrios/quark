# Architecture

Quark is currently a Java 25 + Quarkus application with a typed,
event-driven agent runtime. The project is evolving toward a small,
framework-independent execution runtime that can be embedded inside JVM
applications.

This document intentionally separates **current architecture** from
**architectural direction**. It does not describe planned migrations as if they
already exist.

For engineering principles, read [`MANIFESTO.md`](MANIFESTO.md). For the
long-term product/architecture thesis, read
[`docs/vision/runtime-platform.md`](docs/vision/runtime-platform.md).

---

# Current architecture

The current implementation is the result of the walking-skeleton sequence
recorded in [ADR 0003](docs/adr/0003-walking-skeleton-first-plan-sequencing.md).

The project first validated a real Telegram -> Gemini conversational loop,
then extracted runtime boundaries from that working code.

Today, the main execution path is:

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
      └── emit a terminal AgentEvent
              │
              ▼
       Telegram renderer
```

The current runtime contract is:

```java
Multi<AgentEvent> execute(TurnRequest request)
```

`AgentEvent` is a sealed interface with the current lifecycle variants:

- `TurnStarted`
- `MemoryLoaded`
- `ModelInvoked`
- `TokenEmitted`
- `ModelCompleted`
- `TurnCompleted`
- `TurnFailed`

Every event carries a `turnId`. The runtime guarantees one terminal event per
turn and then completes the stream normally. Failures are represented as
runtime events rather than requiring transport-specific exception semantics.

That event model is a real implementation today, not merely a future design.

---

## Current package boundaries

```text
com.quark
├── core/        — execution contracts and shared types
├── runtime/     — AgentRuntime orchestration
├── memory/      — ChatMemoryStore + in-memory implementation
├── provider/    — ModelGateway + Gemini implementation
└── adapter/     — transport adapters and renderers
```

The intent of these boundaries remains useful:

- transports should not orchestrate turns;
- runtime code should not depend on concrete transports;
- provider-specific integration should remain behind a provider boundary;
- memory should remain replaceable behind a store boundary;
- execution events should remain transport-neutral.

---

## Current framework coupling

The runtime is **not framework-independent yet**.

Current coupling includes:

- `AgentRuntime` is annotated with CDI `@ApplicationScoped` and constructed
  through `@Inject`;
- runtime streaming uses SmallRye Mutiny `Multi`;
- `ModelGateway` exposes `Multi<String>` as part of its SPI;
- Quarkus owns application startup, configuration, DI, and current lifecycle;
- Quarkus logging is used inside the runtime;
- the Gemini implementation uses Quarkus LangChain4j integration;
- the build is a single Quarkus application module.

These are properties of the current walking skeleton, not principles the
future runtime must preserve.

The original decision to use a single Quarkus module is recorded in
[ADR 0002](docs/adr/0002-single-quarkus-module-archunit-boundaries.md). That
ADR remains valuable historical context. The new framework-independent
direction means its long-term assumptions will need to be revisited by a
future superseding decision rather than silently rewritten here.

---

# Architectural direction

The intended destination is not "replace Quarkus with another application
framework."

The intended change is to stop making an application framework the owner of
Quark's runtime semantics.

Historically:

```text
Quarkus application
       │
       └── Quark runtime
```

Direction:

```text
Host application
       │
       ▼
   Quark runtime
```

The host may eventually be Spring Boot, Quarkus, Ktor, Micronaut, plain JVM,
a CLI process, or another compatible JVM environment.

The rule is:

> Framework is not runtime.

Quarkus may later become a first-class integration, but the core runtime
should not require Quarkus in order to exist.

---

## Agent framework is not runtime

Quark also should not require a single higher-level AI framework.

Potential hosts of agent logic include:

```text
Spring AI
LangChain4j
provider SDKs
custom agent logic
        │
        ▼
      Quark
        │
 execution semantics
```

Those relationships are architectural direction only. No corresponding
integration modules are being designed in this documentation phase.

Quark's concern should remain the execution boundary itself.

---

# Execution semantics

The core model is a turn lifecycle, not a final response.

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

Future production pressure may justify additional semantics such as:

```text
ContextLoaded
ProviderSelected
ToolRequested
PolicyEvaluated
ApprovalRequired
ToolStarted
ToolCompleted
TurnCancelled
CheckpointCreated
TurnResumed
```

These names are examples, not commitments to a schema.

Any future extension must preserve the principle that meaningful execution
behavior is represented explicitly and observably rather than reconstructed
from framework logs or prompt conventions.

---

# Observability boundary

Observability should be a projection of execution semantics.

Current state:

- `turnId` correlation exists;
- lifecycle information is available through `AgentEvent`;
- logging exists;
- richer metrics/tracing remain incomplete.

Direction:

- runtime events should be consumable by OpenTelemetry-oriented integrations;
- phase latency should be measurable;
- provider/model/tool information should become inspectable as the runtime
  gains those capabilities;
- debugging and future replay should build on structured execution data rather
  than transport-specific logs.

Quark should not require a proprietary telemetry backend.

---

# Control boundary

Control semantics are mostly future direction today.

If production use cases justify them, concerns such as:

- tool permissions;
- policy evaluation;
- approval requirements;
- budgets;
- timeout/cancellation;
- provider/model restrictions;

should become explicit runtime decisions rather than hidden prompt
instructions.

This document intentionally does not define a policy DSL, approval service, or
new package hierarchy. Those abstractions have not earned their shape yet.

---

# Reliability and recovery boundary

Quark should eventually make failures easier to understand and recover from,
but it should not become a general-purpose workflow engine.

Possible future capabilities include:

- deterministic test surfaces;
- execution replay for debugging;
- checkpoints;
- resume semantics;
- integration with durable execution systems.

If long-running durable workflows become necessary, the default architectural
bias should be integration with systems designed for that responsibility
rather than rebuilding a distributed workflow engine inside Quark.

---

# Open-source boundary

The runtime should remain operationally useful without a hosted control plane.

The intended open-source direction includes the execution layer itself and the
primitives required to run, observe, constrain, test, and debug it locally.

A possible future commercial control plane may coordinate organization-wide
concerns such as fleet visibility, centralized policy distribution, hosted
traces, approvals, RBAC/SSO, audit, and managed evaluations.

That commercial product does not exist today and is not a reason to compromise
the local runtime.

Architectural constraint:

> If a future control plane disappears, the local runtime continues executing
> according to local configuration.

---

# Near-term migration direction

The next engineering phase is expected to proceed incrementally:

1. introduce Kotlin alongside Java;
2. preserve current runtime behavior with tests;
3. move public streaming contracts away from Quarkus/Mutiny-specific types;
4. migrate streaming semantics toward Kotlin Coroutines / `Flow`;
5. make runtime construction and lifecycle independent from CDI;
6. remove Quarkus from the runtime core gradually;
7. retain Quarkus as a potential optional host integration;
8. add new production semantics only when real use cases justify them.

This is not a commitment to a specific module graph.

In particular, this phase does **not** pre-decide:

- Gradle subproject topology;
- a plugin framework;
- PF4J;
- a harness layer;
- a policy DSL;
- a workflow engine;
- multi-agent orchestration;
- cloud APIs;
- provider abstraction shape beyond what implementation pressure requires.

Those decisions should be made through implementation work and ADRs when they
become concrete.

---

# Historical architecture

The original bootstrap and MVP documentation remains valuable because it
explains why the current runtime looks the way it does.

Key records include:

- [ADR 0001](docs/adr/0001-event-driven-agentevent-stream.md) — why execution
  became a typed event stream;
- [ADR 0002](docs/adr/0002-single-quarkus-module-archunit-boundaries.md) — why
  the current implementation chose a single Quarkus module;
- [ADR 0003](docs/adr/0003-walking-skeleton-first-plan-sequencing.md) — why
  runtime abstractions were extracted only after the conversational loop
  worked;
- [ADR 0007](docs/adr/0007-agent-runtime-owns-conversation-memory.md) — why the
  runtime owns conversation memory semantics.

These ADRs are historical decisions, not disposable documentation. When the
new direction invalidates one of their assumptions, write a superseding ADR
rather than editing history.

---

# Architectural invariants

The following principles should survive implementation changes:

```text
Transport != Runtime
Framework != Runtime
Agent Framework != Runtime
```

and:

- execution remains explicit;
- streaming remains fundamental;
- meaningful lifecycle state remains observable;
- the runtime remains small;
- integrations are preferred over unnecessary replacement;
- abstractions must earn themselves.

Those invariants matter more than any specific framework, reactive library,
package layout, or build topology used by the current walking skeleton.
