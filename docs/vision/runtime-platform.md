# Long-term direction

Quark is evolving toward a small, embeddable agent execution runtime for the
JVM.

This document explains the product thesis behind that direction. It does not
describe shipped capability. Read [`README.md`](../../README.md) for how to run
Quark today and [`ARCHITECTURE.md`](../../ARCHITECTURE.md) for the canonical
technical state and migration direction.

The thesis is simple:

> building an agent is becoming easier; operating agent behavior inside real
> systems with clear execution semantics is still hard.

Quark should focus on that execution layer.

---

## The production problem

Different teams may build agents with different libraries, provider SDKs, and
application frameworks. That freedom is useful, but organizations eventually
still need common answers to questions such as:

- what happened during this execution?
- which model/provider participated?
- which external actions were requested?
- what policy allowed or denied them?
- how long did each phase take?
- where did a turn fail?
- can execution be cancelled, tested, diagnosed, or recovered?

Quark should provide a common execution boundary without requiring every team
to adopt the same higher-level agent framework.

```text
agent frameworks / provider SDKs / custom agent logic
                         │
                         ▼
                       Quark
                         │
        explicit agent execution semantics
```

The goal is not to own prompts, RAG, every provider integration, workflow
orchestration, or application lifecycle. Those concerns should remain with
systems better suited to them.

---

## What Quark should become useful for

The long-term value is strongest in concerns that appear after a prototype
works:

- consistent execution lifecycle and correlation;
- model/provider visibility;
- tool/action visibility;
- latency and cost attribution;
- explicit cancellation and timeout semantics;
- policy and approval boundaries;
- observability integrations;
- deterministic testing surfaces;
- replay for debugging;
- recovery/checkpoint semantics where justified;
- integration with durable execution infrastructure rather than replacement of it.

These are areas of exploration, not a committed feature checklist.

The project should add capabilities incrementally and only after concrete use
cases justify their shape.

---

## Ecosystem position

Quark should complement the JVM ecosystem rather than compete with all of it.

A team might build agent logic with Spring AI, LangChain4j, a provider SDK, or
custom code and still use Quark for execution semantics. A Quark runtime may
be hosted by Spring Boot, Quarkus, Ktor, Micronaut, or a plain JVM process.

Those relationships are intentional, but the corresponding integration APIs
have not been designed yet.

The project's value should come from the execution layer being useful across
those choices.

---

## Open-source-first runtime

The runtime should remain operationally useful without a hosted Quark service.

A reasonable open-source boundary includes the capabilities necessary to run,
observe, constrain, test, and debug execution locally, plus ecosystem
integrations that make the runtime adoptable.

A separate commercial product may eventually make sense when organizations
need coordination across many services and teams. Examples include:

- fleet and runtime inventory;
- centralized policy distribution;
- hosted trace storage and search;
- team-level cost/latency analytics;
- approval workflows;
- RBAC / SSO;
- immutable audit and governance;
- agent/runtime registry;
- managed evaluations;
- enterprise deployment and support.

This is a possible product boundary, not a current product commitment.

One constraint should survive any future commercialization:

> the local runtime must not require a hosted control plane in order to keep
> executing according to local configuration.

That keeps the open-source runtime trustworthy and keeps the commercial value
focused on organization-wide operations rather than artificial lock-in.

---

## Deliberate boundaries

Quark should remain narrower than an all-in-one AI platform.

It should not need to become:

- a personal-assistant distribution;
- a general-purpose agent framework;
- a provider-SDK collection;
- an application/web framework;
- a no-code platform;
- a general-purpose workflow engine;
- a distributed durable-execution system.

Narrowness is part of the product strategy: a focused runtime is easier to
embed, understand, trust, and integrate.

---

## How the project should evolve

The long-term direction should not dictate a speculative module graph or a
large set of abstractions in advance.

The immediate technical migration belongs in [`ARCHITECTURE.md`](../../ARCHITECTURE.md).
This vision only sets the decision filter:

1. does the capability improve agent execution itself?
2. does it make execution easier to observe, control, test, or recover?
3. is Quark the right layer to own it, or should Quark integrate with an existing system?
4. has a real use case earned the abstraction?

If the answer to those questions is weak, the feature probably does not belong
in the core runtime.

---

## Why the name still fits

The name began as a nod to Quarkus.

It also fits the intended product independently: a small fundamental building
block that participates in larger systems without trying to become the whole
system.
