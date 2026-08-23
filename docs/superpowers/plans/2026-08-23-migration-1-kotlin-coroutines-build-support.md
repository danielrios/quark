# Migration 1 — Kotlin and Coroutines Build Support

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.

**Decision:** [ADR 0008](../../adr/0008-framework-independent-runtime-and-kotlin-migration.md)
(framework-independent runtime, Kotlin/JVM migration). This plan is ADR 0008 **Migration 1** —
the first actual migration increment after the ADR. It is deliberately tiny: build capability
only, zero runtime migration.

**Goal:** Kotlin and Java compile and test side by side in the existing single Gradle
module; `kotlinx-coroutines-core` is on the classpath for the immediately following
contracts work (Migration 3); a minimal Java<->Kotlin interoperability smoke test proves the
toolchain. Build stays green; no runtime behavior changes; no existing Java class migrates.

**Precondition (Phase 0) — DONE, 2026-08-23:** the unfinished Plan 5 experiment (NIM provider +
provider preference) was intentionally discarded after the pre-Plan-5 runtime was restored.
The experiment was small, incomplete, non-compiling, and coupled orchestration more deeply
to CDI; it is not a compatibility target for the migration. NIM remains only a future
candidate second provider, to be implemented fresh against the neutral provider boundary if
that need returns. The restored baseline was verified green with `./gradlew test` (53 tests,
0 failures), `spotlessCheck`, and `build`. Migration 1 starts from that baseline and re-proves
it as its first task.

**Tech stack:** Java 25 (unchanged), Gradle 9.5.1 + Kotlin JVM plugin (latest stable at
implementation time), Kotlin stdlib, `kotlinx-coroutines-core`. Existing Quarkus 3.35.4
stack untouched.

**Test gate:** `./gradlew test` after every task (documented MCP fallback per
`docs/progress.md`); `./gradlew spotlessCheck` before opening the PR. Baseline: 53 tests,
0 failures (Phase 0 gate, recorded 2026-08-23) — re-prove on the working branch first.

---

## Explicit non-goals (earned-dependency discipline)

- No production Kotlin source files. The only `.kt` files live in `src/test/kotlin`.
- No `io.quarkus:quarkus-kotlin` — the core runtime will not be CDI-managed; add it only
  if host-side Kotlin CDI beans ever need Arc enrichment.
- No `kotlinx-serialization` — nothing serializes yet.
- No `kotlinx-coroutines-test` — lands with the first Flow-based test (Migration 3), not
  before a test needs it.
- No Kotlin DSL rewrite of `build.gradle.kts` (it already is `.kts`).
- No runtime, contract, or test behavior changes of any kind.
- No NIM/provider-preference revival. A second provider belongs after the neutral provider
  boundary exists and should be implemented against that boundary from scratch if still useful.

---

## File Map

| File | Status | Responsibility |
|------|--------|----------------|
| `build.gradle.kts` | Modify | Add `kotlin("jvm")` plugin, Kotlin/Java source-set coexistence config, `kotlinx-coroutines-core` dep, Spotless Kotlin block |
| `src/test/kotlin/com/quark/build/KotlinInteropSmokeTest.kt` | **Create** | Kotlin test calling existing Java classes (records, store) |
| `src/test/java/com/quark/build/JavaCallsKotlinTest.java` | **Create** | Java test calling a tiny Kotlin helper (test-tree only) |
| `docs/progress.md` | Modify | Task pointer + gate result entry |

Nothing else changes. `settings.gradle.kts`, CI workflow, `application.properties`, and all
main sources are untouched.

---

## Tasks

### Task 1 — Kotlin JVM plugin, plugin-only green point

- [ ] Apply `kotlin("jvm")` with a pinned version in `build.gradle.kts` `plugins {}` block.
- [ ] Verify compatibility matrix before pinning: Kotlin Gradle plugin vs Gradle 9.5.1, and
      the plugin's maximum supported `jvmTarget` against the Java 25 toolchain. If JVM 25
      bytecode is not yet supported for Kotlin, pin `kotlin { jvmToolchain(...); compilerOptions
      { jvmTarget = <max supported> } }` and record the mixed-target fact in the PR body —
      mixed class-file versions in one jar are valid; do not downgrade the Java toolchain.
- [ ] `./gradlew compileJava compileTestJava test` green with zero Kotlin sources (plugin
      wiring only). Commit: `build: add Kotlin JVM plugin`.

### Task 2 — Coroutines dependency

- [ ] Add `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:<latest stable>")`.
      Version aligned with the Kotlin plugin's recommended stdlib/coroutines pairing; no BOM
      needed for a single library.
- [ ] Build green. Commit: `build: add kotlinx-coroutines-core`.

### Task 3 — Java/Kotlin interoperability smoke (test-tree only)

- [ ] `KotlinInteropSmokeTest.kt` (in `src/test/kotlin`): construct
      `InMemoryChatMemoryStore(20)` and `TurnRequest.of("s1", "hi")` from Kotlin, append/load
      round-trip, assert snapshot. Exercises Java classes from Kotlin, including records and
      the existing public SPI surface.
- [ ] `JavaCallsKotlinTest.java` (in `src/test/java`): call a tiny Kotlin helper object
      defined in the test tree (e.g. `KotlinTestProbe.describe(TurnRequest)` returning a
      string) and assert on its output. Exercises Kotlin from Java, including nullability
      and default handling at the boundary.
- [ ] `./gradlew test` green; total test count = baseline + 2. Commit:
      `test: prove Java/Kotlin interop`.

### Task 4 — Spotless + CI hygiene

- [ ] Add a `spotless { kotlin { target("src/**/*.kt") } }` block using the same minimal
      string-level rules as Java (`trimTrailingWhitespace()`, `endWithNewline()`); keep the
      heavy-formatter avoidance rationale from the Java block (JDK 25 javac-internal
      breakage).
- [ ] `./gradlew spotlessCheck` green. CI needs no workflow edits (`gradlew test` +
      `spotlessCheck` pick up Kotlin automatically).
- [ ] Commit: `build: spotless kotlin rules`.

### Task 5 — Docs close-out

- [ ] `docs/progress.md`: task pointer -> Migration 2 (lock streaming/cancellation semantics,
      ADR 0008); record gate result and any toolchain facts discovered in Task 1.
- [ ] Commit: `docs(progress): migration step 1 close-out`.

---

## Risks / notes

- **KGP × Gradle 9.5.1 × JDK 25**: the only real unknown. Task 1 resolves it first and
  cheaply; the fallback (mixed bytecode targets) is safe and reversible.
- **Quarkus incremental compilation** with mixed Java/Kotlin sources: watch for
  `quarkusDev` restart friction; not a blocker for test-only Kotlin.
- **Native image** (`Dockerfile.native` builds): Kotlin stdlib adds footprint; accepted by
  ADR 0008 D2. No action this PR.
- **WIP = 1** (CLAUDE.md §2): this PR is build-only; any contract/runtime change belongs to
  Migration 2, planned separately.
