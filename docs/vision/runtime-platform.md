# Long-term direction

Quark is evolving toward a small, embeddable, streaming-first agent execution
runtime for the JVM.

This document describes direction, not shipped capability. Read
[`README.md`](../../README.md) and [`ARCHITECTURE.md`](../../ARCHITECTURE.md)
for the current implementation.

The central thesis is simple:

> building an agent is becoming easier; running agent behavior inside real
> systems with explicit execution semantics is still hard.

Quark should focus on that execution layer.

---

## Why a runtime

Agent applications often mix together several concerns:

- agent logic and prompts;
- model/provider SDKs;
- context and memory;
- tool invocation;
- transport;
- rendering;
- business logic;
- retries and failure handling;
- observability.

That coupling can be acceptable for a prototype and painful in production.
The challenge becomes larger when different teams choose different agent
frameworks but the organization still wants common answers to questions such
as:

- which model executed this turn?
- what context was used?
- what actions were requested?
- what policy allowed or denied them?
- what was the execution timeline?
- where did the turn fail?
- can it be cancelled, diagnosed, tested, or eventually recovered?

Quark's intended answer is not to own every higher-level AI abstraction.
Instead, it should model execution itself.

```text
Spring AI / LangChain4j / provider SDKs / custom agent logic
                         │
                         ▼
                       Quark
                         │
              execution semantics
                         │
          ┌──────────────┼──────────────┐
          │              │              │
       observe        control         recover
```

The named integrations above are examples of ecosystem relationships, not
implemented modules.

---

## A turn is an execution lifecycle

Quark should never collapse its fundamental model into `prompt -> string`.

The current runtime already exposes a typed event stream:

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

That contract is intentionally more expressive than a text stream because
agent execution eventually contains more than tokens.

Future pressure may justify additional semantics such as:

- provider selection;
- tool request / execution / result;
- policy evaluation;
- approval suspension and continuation;
- cancellation;
- budget checks;
- checkpoints;
- replay or recovery metadata.

These are directions, not a feature list.

The durable principle is that meaningful execution state should remain
observable as structured events.

---

## Framework independence

Quark began as a Quarkus application. That was useful: Quarkus provided a
fast application skeleton while Telegram, streaming, memory, provider
interaction, and the runtime event model were validated end to end.

The current implementation still reflects that history. `AgentRuntime` is a
CDI bean and the runtime/provider streaming contracts expose SmallRye Mutiny
`Multi`.

The destination is different:

```text
Host application
       │
       ▼
   Quark runtime
```

The runtime should eventually be embeddable in hosts such as:

- Spring Boot;
- Quarkus;
- Ktor;
- Micronaut;
- plain Kotlin/JVM applications;
- CLI or always-on JVM processes;
- other JVM environments where its runtime model fits.

This does not make Quarkus a mistake or a competitor. It changes Quarkus from
foundation to potential integration.

The rule is:

> the runtime must not belong to the application framework hosting it.

---

## Relationship with agent frameworks

Quark should not compete with Spring AI, LangChain4j, or provider SDKs by
reimplementing all of their abstractions.

Those systems can remain responsible for concerns such as model APIs, prompt
construction, RAG helpers, provider-specific integrations, or higher-level
agent patterns.

Quark should become useful when teams need common execution semantics across
those choices.

A future application might use:

```text
Spring AI
    │
    ▼
Quark execution boundary
```

while another uses:

```text
custom provider SDK code
    │
    ▼
Quark execution boundary
```

Whether those specific integrations should exist, and what shape they should
take, must be earned by implementation pressure.

---

## Design principles

### Explicit over magical

Execution should be understandable by reading code and consuming runtime
events. Hidden orchestration is a cost, not a feature.

### Streaming-first

Streaming is the native execution model. The concrete JVM streaming primitive
may change during the framework-independence work; the semantic commitment to
incremental execution does not.

### Observable by design

Structured execution events should be the source from which logs, metrics,
traces, timelines, cost analysis, and future replay tooling can be projected.

### Transport-agnostic execution

Telegram, HTTP, SSE, WebSocket, CLI, voice, and future transports project the
same execution model. They do not define it.

### Framework-agnostic execution

Quark's core semantics must not require Spring, Quarkus, Ktor, Micronaut, or
another application framework.

### Explicit control

If tools, approvals, policies, budgets, provider restrictions, or cancellation
become part of production execution, their decisions should be explicit
runtime semantics rather than prompt conventions.

### Additive evolution where justified

New capability should extend the runtime without forcing unrelated transports
or integrations to understand implementation details. This does not mean the
current public contract is frozen forever; early-stage contracts may change
when real use cases prove them insufficient.

### Integrate instead of replace

Quark should prefer integrations with mature JVM and distributed-systems
infrastructure rather than rebuilding adjacent systems.

### Architecture must earn itself

No plugin framework, workflow engine, harness, module split, policy language,
cloud control plane, or multi-agent layer exists merely because it seems
architecturally elegant. Each must solve a demonstrated problem.

---

## Production semantics

The long-term value of Quark is strongest in concerns that become important
after an agent prototype works.

Potential areas include:

- execution lifecycle and correlation;
- cancellation and timeout semantics;
- provider/model visibility;
- tool/action visibility;
- policy enforcement boundaries;
- approval primitives;
- OpenTelemetry integration;
- deterministic testing surfaces;
- execution replay for debugging;
- checkpoint/resume semantics where justified;
- integration with durable execution systems rather than replacement of them.

The project should add these incrementally. "Production runtime" is a design
orientation, not a claim that every production concern is already solved.

---

## Open-source-first runtime

The execution layer should remain operationally useful without a Quark-hosted
service.

A reasonable long-term open-source boundary includes concepts such as:

- runtime APIs and execution contracts;
- event semantics;
- local policy enforcement;
- OpenTelemetry hooks;
- local test and debugging utilities;
- framework/provider integrations;
- plugin or integration SDKs if real demand eventually justifies them.

A separate commercial product may eventually make sense around
organization-wide coordination:

- fleet visibility;
- centralized policy distribution;
- hosted trace storage;
- team dashboards;
- approval workflows;
- RBAC / SSO;
- audit and governance;
- cross-service agent registry;
- managed evaluations;
- enterprise deployment and support.

This is not a current product commitment.

The important architectural constraint is:

> If a future Quark control plane disappears, the customer's application and
> local runtime continue executing according to local configuration.

---

## Deliberate non-goals

Quark is not intended to become:

- a ChatGPT clone;
- a personal assistant distribution;
- a Hermes or NanoClaw replacement;
- a DeepSeek Harness clone;
- a replacement for Spring AI or LangChain4j;
- a collection of every provider SDK;
- a no-code or low-code platform;
- a web framework;
- an application framework;
- a mandatory DI container;
- a general-purpose workflow engine;
- a distributed durable-execution replacement for Temporal;
- an all-in-one AI platform.

Narrowness is a feature.

---

## Near-term sequence

The immediate direction after this documentation realignment is expected to
be incremental:

1. introduce Kotlin alongside Java;
2. decouple public runtime contracts from Quarkus/Mutiny-specific types;
3. move streaming semantics toward Kotlin Coroutines / `Flow`;
4. make the runtime framework-independent and embeddable;
5. preserve current behavior through tests;
6. gradually remove Quarkus from the runtime core;
7. treat Quarkus as a potential optional integration later;
8. introduce additional production execution semantics only when real use
   cases justify them.

This sequence may change as implementation teaches us more.

---

## Why the name still fits

The name started as a nod to Quarkus.

It also describes the desired character of the project independently: a small,
fundamental building block from which larger agent systems can be composed.
The metaphor should remain secondary to the engineering constraints, but the
name no longer needs to bind the runtime to a specific application framework.
