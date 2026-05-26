# 0002 — Single Quarkus module with package boundaries enforced by ArchUnit

**Status:** Accepted, 2026-05-25
**Deciders:** Daniel + brainstorming session
**Context refs:** [`docs/superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md`](../superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md) §3.1, §3.4

## Context

The slice 1 design carves the codebase into clear layers — `core`,
`runtime`, `memory`, `provider`, `adapter`. These boundaries need to be
enforceable: the runtime must not depend on a concrete provider, adapters
must not depend on each other, `core` must not depend on Quarkus or
LangChain4j.

Two ways to enforce layering in a Gradle codebase:

1. **Gradle subprojects.** Each layer becomes its own subproject with an
   explicit `dependencies { ... }` block. The build refuses to compile a
   dependency that crosses a boundary.
2. **Single project, package-level rules.** All sources live in one
   subproject; an ArchUnit test suite asserts the dependency graph at
   build time.

Subprojects are stronger (compile-time vs test-time enforcement) but cost
significantly in build complexity: separate `build.gradle.kts` per layer,
inter-project dependencies to maintain, dev-mode reload across subprojects
is fiddly, the Quarkus plugin needs to be applied consistently, and IDE
import becomes noisier. They pay off when teams genuinely need separate
deployable units or when binary stability between layers matters.

`quark` is one deployable today and for the foreseeable future. No team
needs to consume `core` independently. The cost of subprojects buys
nothing functional; it buys process discipline that ArchUnit can also
provide at a fraction of the build complexity.

## Decision

Single Quarkus module. One `build.gradle.kts`. Layer boundaries are
expressed as Java packages under `com.quark.*` and enforced by ArchUnit
tests in the `archtest` test package. Each boundary rule is its own
`@Test` so a violation fails CI with a precise per-rule message pointing
at the offending package.

The enforced rules (from spec §3.4) include:

- `core` may not depend on Quarkus, LangChain4j, Telegram libraries, or
  Jakarta REST.
- `runtime` may depend on `core`, `memory.*`, and the `provider` SPI but
  not on concrete `provider.*` implementations or any `adapter.*`.
- `provider.<name>` may depend on `core`, the `provider` SPI, and
  LangChain4j; it may not depend on `runtime`, `adapter.*`, or another
  `provider.<other>`.
- `adapter.*` may depend on `core`, `runtime`, and `memory.preference`
  but not on `provider.*`, on another `adapter.*`, or on LangChain4j.
- `memory.*` may depend on `core` (and LangChain4j inside `memory.chat`
  for the `ChatMemoryStore` SPI binding) but not on `runtime`,
  `adapter.*`, or `provider.*`.

## Consequences

### Positive
- Build stays simple and Quarkus dev mode is uncomplicated.
- IDE import is one Gradle module — fast, predictable.
- Rules are expressed where engineers already look (test sources, build
  failures) rather than scattered across multiple `build.gradle.kts`.
- New layers or rule refinements are one test method, not a subproject
  scaffolding exercise.

### Negative
- Enforcement is at *test* time, not compile time. A developer who runs
  `./gradlew compileJava` alone can introduce a boundary violation that
  only surfaces at `./gradlew test` or in CI.
- Nothing prevents a teammate from disabling an ArchUnit rule to ship
  faster. Code review is the social check.
- If `quark` ever genuinely needs to publish `core` as a separate
  artefact, this decision must be revisited — splitting into subprojects
  later is a real refactor.

### Mitigations
- ArchUnit tests live in their own dedicated package (`archtest`) with a
  distinctive name, so disabling them is visible in PRs.
- CI runs `./gradlew test`, not `compileJava`, before merge — the test-time
  gap closes at the merge boundary.
- A note in `CLAUDE.md` and `ARCHITECTURE.md` directs contributors to read
  the ArchUnit rules before adding a new package.

## Revisit if

- The project gains a second deployable that needs to consume `core`
  without `runtime`.
- `core` or `provider` SPI stabilises enough to publish externally with
  semver guarantees.
- The test-time gap causes a real incident.
