# The Quark Manifesto

Quark exists to make agent execution explicit.

The project is intentionally narrow: it should provide clear execution
semantics without trying to become the framework that owns the whole agent
stack.

---

## Explicit over magical

Agent execution should be understandable by reading the code and observing
runtime events.

Meaningful behavior should not disappear inside hidden orchestration. When a
capability becomes part of execution, its state transitions and decisions
should be inspectable.

A runtime should not behave like a black box.

---

## Streaming is the execution model

Streaming is not a UI optimization.

Agent execution unfolds over time. Tokens, lifecycle transitions, tool calls,
policy decisions, failures, and recovery signals are temporal events.

The concrete streaming primitive may change. The semantic commitment should
not: execution is a progression of meaningful events, not only a final
response string.

---

## Transport is not runtime

Telegram, HTTP, SSE, WebSocket, CLI, voice, and future transports are delivery
mechanisms.

They project execution. They do not define it.

Adapters should render runtime behavior without owning orchestration.

---

## Framework is not runtime

The runtime must not belong to the application framework hosting it.

Frameworks may provide lifecycle, dependency injection, configuration,
networking, and integrations. Those are host concerns.

Quark's execution semantics should remain independent enough to embed inside
different JVM environments.

Framework integrations are welcome. Framework ownership of the runtime is
not.

---

## Observability is part of execution

A good runtime should make answering "what happened?" straightforward.

Execution should expose enough structured state that logs, metrics, traces,
timelines, cost attribution, and debugging tools can be projections of the
runtime rather than the only place where runtime behavior can be inferred.

Operational clarity is a feature.

---

## Control should be explicit

Production agents eventually need constraints that do not belong inside
prompts.

Permissions, approvals, budgets, timeouts, cancellation, provider
restrictions, and similar decisions should become explicit runtime semantics
when real use cases justify them.

Quark should not hide control policy inside conventions that are difficult to
observe or test.

---

## Small systems compose better

Quark prefers small contracts and understandable components over framework
hierarchies that try to predict every future use case.

The runtime should provide the semantics it is uniquely responsible for and
integrate with the surrounding ecosystem for the rest.

---

## Abstractions must earn themselves

Architecture emerges from pressure, not fashion.

New layers, plugin systems, module boundaries, policy languages, workflow
engines, or orchestration concepts should not exist merely because similar
projects have them.

A new abstraction must solve a demonstrated problem.

---

## Integrate instead of replace

Quark should participate in the JVM and distributed-systems ecosystems rather
than rebuild adjacent systems unnecessarily.

The goal is not to own the entire agent stack.

The goal is to provide a focused execution layer that works with different
ways of building and hosting agents.

---

## Production semantics over demo convenience

Prototype agents optimize for producing a useful response quickly.

Production agents eventually need stronger semantics around lifecycle,
cancellation, failure visibility, tracing, policy enforcement, testability,
compatibility, and recovery.

Quark should orient its architecture toward those problems without pretending
they are all solved today and without making the runtime large for their own
sake.

---

## Open source should remain operationally complete

The runtime layer should remain useful without a hosted service.

Execution contracts, local enforcement, observability hooks, test utilities,
and local debugging capabilities should not depend on a remote control plane
to function.

A future hosted product may coordinate organization-wide concerns, but it
must not become a prerequisite for local agent execution.

---

## What Quark is not

Quark is not:

- a personal assistant product;
- an all-in-one agent platform;
- a replacement for agent frameworks or provider SDKs;
- an application or web framework;
- a mandatory dependency-injection container;
- a general-purpose workflow engine;
- an "AGI framework."

Narrowness is a feature.

---

## Build the smallest thing that can evolve

Every increment should remain understandable and runnable.

Every migration should preserve observable behavior where that behavior is
still desired.

Every abstraction should come from pressure.

The ambition can be long-term.

The implementation stays grounded.
