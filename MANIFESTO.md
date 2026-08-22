# The Quark Manifesto

Quark is a small, embeddable, streaming-first agent execution runtime for the
JVM.

Its purpose is not to make calling a model look magical. Its purpose is to
make agent execution explicit enough to understand, observe, control, test,
and evolve in production systems.

Quark began as a Quarkus-based experiment. The experiment succeeded in one
important way: it exposed real runtime seams through working code. The next
step is to preserve those semantics while making the runtime independent from
the framework that currently hosts it.

---

## Explicit over magical

Agent execution should be understandable by reading the code and observing
runtime events.

Meaningful actions should not disappear inside hidden orchestration:

- model invocation;
- context and memory access;
- streamed output;
- tool requests and results;
- policy decisions;
- approvals;
- cancellation;
- failures.

Not every item exists in the runtime today. The principle is that, when a
capability becomes part of execution, its behavior should be explicit rather
than hidden behind framework magic or prompts.

A runtime should not behave like a black box.

---

## Streaming is the execution model

Streaming is not a UI optimization.

Agent execution evolves over time. Tokens, lifecycle transitions, tool calls,
policy decisions, failures, and future recovery signals are temporal events.
The runtime should expose that progression naturally.

The current runtime already expresses a turn as `Multi<AgentEvent>`. The
underlying streaming technology may change as Quark becomes
framework-independent, but the semantic commitment does not: execution is a
stream of meaningful events, not merely a final response string.

---

## Transport is not runtime

Telegram, REST, SSE, WebSocket, CLI, voice, and future transports are delivery
mechanisms.

They project execution. They do not define it.

Adapters may decide how to render `TokenEmitted`, failures, or future tool and
approval events, but transport-specific concerns must not become orchestration
semantics.

---

## Framework is not runtime

The runtime must not belong to the application framework hosting it.

Quarkus was the right tool for building the original walking skeleton quickly.
It provided application lifecycle, dependency injection, configuration,
reactive primitives, and integrations while the project was discovering what
the runtime actually needed to be.

Those discoveries now need to stand independently.

Spring, Quarkus, Ktor, Micronaut, plain JVM applications, and other hosts
should eventually be able to embed the same Quark execution semantics.
Framework integrations are welcome; framework ownership of the runtime is not.

---

## Observability is part of execution

A good agent runtime should make answering "what happened?" straightforward.

Execution state should expose enough structured information to understand:

- what stage ran;
- which provider or model participated;
- how long stages took;
- what tools or external actions were requested;
- what failed;
- where a turn ended.

Logs, metrics, traces, timelines, cost attribution, and future replay tooling
should be projections of execution semantics rather than the only place where
those semantics exist.

Operational clarity is a feature.

---

## Control should be explicit

Production agents eventually need constraints that do not belong inside
prompts.

Examples include:

- tool permissions;
- policy evaluation;
- approval requirements;
- budgets;
- timeouts;
- cancellation;
- provider restrictions.

Quark does not claim all of these capabilities today. The principle is about
where they belong if and when real use cases justify them: explicit runtime
semantics, not hidden conventions.

---

## Small systems compose better

Quark prefers small contracts and understandable components over framework
hierarchies that try to predict every future use case.

A small runtime that integrates well with the JVM ecosystem is more valuable
than an all-in-one platform that owns every concern.

The runtime should provide the semantics it is uniquely responsible for and
integrate with existing systems for the rest.

---

## Abstractions must earn themselves

Architecture emerges from pressure, not fashion.

`AgentRuntime`, `AgentEvent`, `ModelGateway`, and `ChatMemoryStore` were
extracted only after a working Telegram + Gemini conversational loop created
real reasons for them to exist.

The same standard applies going forward.

Quark should not add a plugin system, workflow engine, harness, policy DSL,
multi-agent coordinator, cloud control plane, or module hierarchy merely
because other AI projects have them.

A new abstraction must solve a demonstrated problem.

---

## Integrate instead of replace

Quark should participate in the JVM ecosystem rather than rebuild it.

Spring AI, LangChain4j, provider SDKs, OpenTelemetry, Temporal, Quarkus,
Spring, Ktor, and other systems may solve concerns adjacent to Quark better
than Quark should.

The goal is not to own the entire agent stack.

The goal is to provide a stable execution layer that can sit underneath or
alongside different ways of building agents.

---

## Production semantics over demo convenience

Prototype agents optimize for getting a useful response quickly.

Production agents eventually need stronger semantics around:

- lifecycle;
- cancellation;
- failure visibility;
- tracing;
- policy enforcement;
- testability;
- compatibility;
- recovery.

Quark should optimize its architecture for those problems without pretending
they are all solved today and without making the runtime large for their own
sake.

---

## Open source should remain operationally complete

The runtime layer should remain useful without a hosted service.

Execution contracts, local enforcement, observability hooks, test utilities,
and local debugging capabilities should not depend on a future Quark control
plane to function.

A hosted or enterprise product may eventually make sense for organization-wide
coordination: fleet visibility, centralized policy distribution, hosted trace
storage, approvals, RBAC/SSO, audit, or managed evaluations.

That is a separate product concern.

A core constraint for any such future is:

> If a Quark control plane disappears, the customer's application and local
> runtime continue executing according to local configuration.

---

## What Quark is not

Quark is not:

- a ChatGPT clone;
- a personal assistant product;
- a Hermes or NanoClaw replacement;
- a DeepSeek Harness clone;
- a replacement for Spring AI or LangChain4j;
- a provider SDK collection;
- a workflow engine;
- a Temporal replacement;
- a mandatory dependency injection container;
- an application or web framework;
- an opinionated all-in-one AI platform;
- an "AGI framework."

Some of these projects may integrate with Quark. None should define its core.

---

## Build the smallest thing that can evolve

Quark started as a tiny conversational service: receive a message, call
Gemini, stream a reply, remember a few turns.

That walking skeleton did its job. It validated behavior and produced real
runtime boundaries.

The project can now evolve toward framework-independent execution semantics,
but it should keep the same discipline:

- every increment remains runnable;
- every migration preserves observable behavior;
- every abstraction comes from pressure;
- current capabilities and future direction remain clearly separated.

The ambition can be long-term.

The implementation stays grounded.
