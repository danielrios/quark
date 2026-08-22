# quark

A small, embeddable, streaming-first agent execution runtime for the JVM.

> Build agents with anything. Run them with control.

Quark models an agent turn as an observable execution lifecycle rather
than a `prompt -> string` call. Its long-term purpose is to provide
explicit execution semantics for agents that need to run inside real JVM
systems: observable, controllable, testable, and eventually recoverable.

Quark is still early. The current implementation is a Java 25 + Quarkus
walking skeleton that runs a Telegram -> Gemini conversational loop with
bounded memory and a typed `Multi<AgentEvent>` runtime stream. The next
engineering phase will make those runtime semantics independent from the
application framework that currently hosts them.

See [`MANIFESTO.md`](MANIFESTO.md) for the engineering principles,
[`ARCHITECTURE.md`](ARCHITECTURE.md) for current and intended boundaries,
and [`docs/vision/runtime-platform.md`](docs/vision/runtime-platform.md)
for the long-term direction.

---

## Why Quark exists

Creating a proof-of-concept agent is becoming easy. Running agents in
production is a different problem.

Once agentic behavior reaches real systems, teams need to answer questions
such as:

- which model and provider executed a turn?
- what context was available?
- which tools were exposed and requested?
- which arguments were passed?
- why was an action allowed, blocked, or held for approval?
- how long did each execution phase take?
- what did a turn cost?
- can execution be cancelled safely?
- can failures be diagnosed without reconstructing behavior from logs?
- can execution eventually be replayed, resumed, or compared across versions?
- can teams use different agent frameworks while sharing execution semantics?

Quark is intended to occupy that layer.

It does not need to own prompts, planning strategies, every model SDK, or the
application framework hosting it. Its core concern is narrower:

> explicit, observable, and controllable execution of agent turns.

Conceptually:

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

The integrations above are direction, not implemented modules today.

---

## What is actually here today

The repository currently contains a Quarkus 3.35.4 / Java 25 / Gradle
application with a running Telegram bot:

- Telegram long polling
- Gemini through `quarkus-langchain4j-ai-gemini`
- bounded per-session conversation memory
- streaming output through throttled Telegram edits
- `/reset`
- an `AgentRuntime` orchestration point
- a sealed `AgentEvent` lifecycle contract
- `Multi<AgentEvent>` streaming
- a `ModelGateway` provider boundary
- a `ChatMemoryStore` memory boundary
- per-turn correlation through `turnId`

The runtime seams were extracted from a working conversational loop rather
than designed speculatively. That sequencing is recorded in
[ADR 0003](docs/adr/0003-walking-skeleton-first-plan-sequencing.md).

### Current coupling

The runtime is not framework-independent yet.

Today:

- `AgentRuntime` is a CDI `@ApplicationScoped` bean;
- the public streaming surface uses SmallRye Mutiny `Multi`;
- `ModelGateway` also exposes `Multi`;
- Quarkus owns application boot, DI, configuration, and the current host lifecycle;
- LangChain4j remains inside the Gemini provider implementation.

This documentation does not pretend that migration has already happened.
The framework-independent runtime is the next architectural direction, not
the current implementation state.

---

## Execution model

A turn is a lifecycle, not just a response string.

The current event contract already models:

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

Future capabilities may add concepts such as provider selection, tool
execution, policy evaluation, approvals, cancellation, checkpoints, and
recovery. Those are design directions, not claims about current features.

The important invariant is that execution remains observable as structured
events instead of becoming hidden orchestration.

---

## Framework independence

Quark began as a Quarkus-based experiment in explicit, streaming agent
execution. Quarkus was useful for building the walking skeleton quickly and
helped expose the runtime boundaries that now deserve to stand on their own.

The intended relationship is changing from:

```text
Quarkus application
       │
       └── Quark runtime
```

toward:

```text
Host application
       │
       ▼
   Quark runtime
```

The host may eventually be Spring Boot, Quarkus, Ktor, Micronaut, plain JVM,
a CLI process, or another JVM environment where the runtime fits.

Quarkus is not being rejected. The goal is simply that the runtime must not
belong to the framework hosting it. A future `quark-quarkus` integration may
provide first-class Quarkus ergonomics without making Quarkus a core runtime
dependency.

---

## What Quark is not

Quark is deliberately not:

- a ChatGPT clone;
- a personal assistant product;
- a Hermes or NanoClaw replacement;
- a DeepSeek Harness clone;
- a replacement for Spring AI or LangChain4j;
- an LLM provider SDK collection;
- a workflow engine;
- a replacement for Temporal or another durable execution system;
- a mandatory DI container;
- an application framework;
- a web framework;
- an opinionated all-in-one AI platform.

Some of these systems may become integrations. They should not become the
identity of the runtime.

---

## Near-term direction

The next engineering phase is intentionally narrower than the long-term
vision:

1. introduce Kotlin alongside the existing Java code;
2. move public runtime contracts away from Quarkus/Mutiny-specific types;
3. migrate streaming semantics toward Kotlin Coroutines / `Flow`;
4. make the runtime embeddable and framework-independent;
5. preserve current behavior through tests;
6. remove Quarkus from the runtime core gradually;
7. keep Quarkus available later as an optional integration;
8. add production execution semantics incrementally as real use cases justify them.

The immediate goal is **not** to build a harness framework, plugin marketplace,
personal-agent distribution, cloud platform, giant policy DSL, or multi-agent
orchestration system.

Architecture must earn itself.

---

## Open-source direction

Quark is intended to remain open-source-first at the runtime layer.

The runtime, execution contracts, local policy enforcement, observability
hooks, test utilities, debugging primitives, and integrations should remain
usable without a hosted control plane.

A future commercial control plane may make sense for organization-wide
concerns such as fleet management, centralized policies, hosted traces,
approvals, RBAC/SSO, audit, and managed evaluations. That product does not
exist today and is not part of the current implementation plan.

A guiding constraint for any future hosted product is:

> If a Quark control plane disappears, the customer's application and local
> runtime should continue executing according to local configuration.

---

## Running the current walking skeleton

Set the required environment variables, then start Quarkus dev mode through
the repository's Claude Code harness:

```bash
export GEMINI_API_KEY=your-gemini-api-key
export TELEGRAM_BOT_TOKEN=your-token-from-botfather
```

```text
quarkus_start   # via the quarkus-agent MCP
```

Tests require no secrets. The `%test` profile disables Telegram and uses a
dummy Gemini key.

For direct Gradle usage:

```bash
./gradlew build
```

Native image support belongs to the current Quarkus application and should
not be interpreted as a commitment for the future framework-independent
runtime.

---

## Repository layout

```text
README.md            — positioning, current state, and project entry point
MANIFESTO.md         — engineering philosophy
ARCHITECTURE.md      — current architecture and intended boundaries
CLAUDE.md            — operating rules for Claude Code sessions
docs/
├── adr/             — historical and load-bearing architectural decisions
├── progress.md      — implementation progress ledger
├── superpowers/     — specs and incremental implementation plans
└── vision/          — long-term direction
.claude/             — Claude Code development harness
```

Historical ADRs may describe decisions that the new direction will revisit.
They should remain as records of why the current walking skeleton looks the
way it does until superseding decisions are made through implementation work.

---

## Why "quark"

The name started as a nod to Quarkus.

It still fits the project independently: a quark is a small fundamental
building block, which matches the goal of keeping agent execution semantics
small, explicit, and composable rather than growing into an application
framework of its own.

---

## Status

Quark is experimental and evolving.

The typed event-driven runtime exists today, but it is still hosted by and
coupled to Quarkus/Mutiny. Framework independence, Kotlin/Coroutines, richer
control semantics, replay, and broader integrations are design directions,
not implemented capabilities.
