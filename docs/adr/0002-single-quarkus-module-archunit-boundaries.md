# 0002 — Single Quarkus module, with package boundaries enforced by ArchUnit at the refactor phase

**Status:** Accepted, 2026-05-25. The single-module decision applies now; ArchUnit enforcement lands with Plan 7 per [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md).
**Deciders:** Daniel + architecture brainstorming sessions.

**Related documents:**
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — "Destination" section, package layout.
- [`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](../superpowers/specs/2026-05-25-agent-runtime-mvp.md) — MVP design.
- [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md) — when boundaries are enforced.

---

## Status note

Two related decisions are recorded here:

1. **Build topology — applies now.** `quark` is a single Quarkus
   module. There are no Gradle subprojects per architectural layer, and
   none are planned.
2. **Boundary enforcement — applies later.** Once Plan 4 introduces
   layered packages (`core/runtime/memory/provider/adapter`),
   architectural boundaries are enforced by ArchUnit tests. The MVP
   keeps a flat `chat/telegram/rest/config/shared` layout where
   ArchUnit would be premature.

---

## Context

`quark` is architecturally layered but operationally monolithic.

The project values:

- fast iteration,
- simple tooling,
- low-friction refactors,
- predictable Quarkus dev-mode workflows,

more than compile-time module isolation at this stage.

The destination architecture (introduced over Plans 4–7) separates the
codebase into explicit layers:

- `core`
- `runtime`
- `memory`
- `provider`
- `adapter`

Those boundaries must remain enforceable once they exist:

- the runtime must not depend on concrete providers,
- adapters must not depend on each other,
- `core` must remain framework-agnostic,
- provider implementations must remain isolated.

Two approaches were considered.

---

## Option 1 — Gradle subprojects

Each layer becomes its own Gradle subproject with explicit dependency
declarations:

```text
:core
:runtime
:provider
:adapter
```

Compile-time dependency rules enforce architectural boundaries
automatically.

## Option 2 — Single module + ArchUnit

All code lives in a single Quarkus application module. Architectural
boundaries are expressed through package structure and validated by
ArchUnit tests:

```text
com.quark.core
com.quark.runtime
com.quark.provider
com.quark.adapter
```

---

## Trade-off analysis

Gradle subprojects provide stronger enforcement — violations fail at
compile time rather than test time.

For `quark`, the operational cost is disproportionate to the gain:

- multiple `build.gradle.kts` files,
- inter-project dependency management,
- noisier IDE imports,
- slower refactors,
- additional Quarkus plugin coordination,
- more fragile hot reload,
- additional dev-mode friction.

Quarkus dev mode, CDI discovery, annotation indexing, and hot reload
all work best in a flat application module.

`quark` is currently one deployable, one runtime, one team, one bounded
codebase. No layer needs independent publishing, binary compatibility
guarantees, or a separate deployment lifecycle. Subprojects would add
build complexity without enabling anything.

---

## Decision

`quark` remains a single Quarkus module.

Architectural boundaries are enforced through:

- package structure,
- package-private visibility,
- ArchUnit tests (once the layered package structure exists).

One `build.gradle.kts` owns the application. Boundary rules live in
dedicated ArchUnit test classes under:

```text
src/test/java/.../archtest
```

Each rule is expressed as its own test method so violations fail with
precise, actionable messages.

---

## Enforced boundaries (destination layout)

Active only after Plan 4 introduces the layered packages. Until then,
the MVP's flat package layout has nothing for these rules to enforce.

- `core`
  - may not depend on Quarkus,
  - may not depend on langchain4j,
  - may not depend on Telegram libraries,
  - may not depend on Jakarta REST.
- `runtime`
  - may depend on `core`, `memory.*`, the `provider` SPI;
  - may not depend on concrete `provider.*` implementations;
  - may not depend on `adapter.*`.
- `provider.<name>`
  - may depend on `core`, the `provider` SPI, langchain4j;
  - may not depend on `runtime`;
  - may not depend on `adapter.*`;
  - may not depend on another `provider.<other>`.
- `adapter.*`
  - may depend on `core`, `runtime`, `memory.preference`;
  - may not depend on `provider.*`;
  - may not depend on another `adapter.*`;
  - may not depend on langchain4j.
- `memory.*`
  - may depend on `core`;
  - may depend on langchain4j only inside `memory.chat`;
  - may not depend on `runtime`, `adapter.*`, or `provider.*`.

---

## Consequences

### Positive

**Simpler operational model.** One Gradle module, one Quarkus
application, one dependency graph. Quarkus dev mode stays fast and
predictable.

**Faster development loop.** Refactors stay low-friction because
boundaries are logical rather than physical. Cross-layer package
movement does not require Gradle restructuring.

**Cleaner developer experience.** IDE import stays simple. Contributors
do not need to understand multi-module Gradle architecture before
contributing.

**Architectural rules stay explicit.** Rules live in executable tests,
not scattered Gradle configuration. Architectural intent is visible and
reviewable.

**Easier architectural evolution.** Adding or refining a boundary is
one package adjustment and one ArchUnit rule, not module scaffolding
and dependency rewiring.

### Negative

**Enforcement happens at test time.** A developer running only
`./gradlew compileJava` can temporarily introduce a boundary violation
that surfaces only during `./gradlew test` or in CI.

**Rules rely partially on discipline.** A contributor could technically
disable an ArchUnit rule. Code review remains part of the enforcement
mechanism.

**Future modularization becomes a real refactor.** If `quark`
eventually needs independently published artifacts, semver between
layers, or multiple deployables, splitting into Gradle subprojects
later requires meaningful restructuring.

---

## Mitigations

- ArchUnit tests live in a dedicated `archtest` package with a visible
  name.
- CI runs the full test suite before merge.
- `ARCHITECTURE.md` and `CLAUDE.md` point contributors at the
  architectural rules before introducing new packages.
- Package-private visibility reinforces local encapsulation inside each
  layer.

---

## Non-goals

This decision does not attempt to:

- create reusable libraries,
- support independent versioning between layers,
- optimize for multiple deployables,
- enforce binary compatibility between packages,
- model deployment boundaries as architectural boundaries.

---

## Revisit if

- `core` needs to be published independently.
- Multiple deployables emerge.
- Provider SPIs stabilise as external extension points.
- The test-time enforcement gap causes real operational problems.
- Quarkus multi-module ergonomics improve substantially.

---

## Long-term impact

This ADR prioritises runtime simplicity, iteration speed, architectural
clarity, and operational ergonomics over maximum compile-time
isolation. The architecture remains strongly layered while the
deployment model stays operationally simple. That trade-off is
considered appropriate for the current scale and maturity of `quark`.
