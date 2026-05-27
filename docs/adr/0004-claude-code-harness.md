# 0004 — Claude Code harness configuration

**Status:** Accepted, 2026-05-26
**Deciders:** Daniel + harness-engineering session
**Context refs:** [`CLAUDE.md`](../../CLAUDE.md), [`.claude/`](../../.claude/)

---

## Context

`CLAUDE.md` defines strict operational rules for AI-assisted development:

* WIP = 1
* baseline tests before and after every change
* no foreground `./gradlew quarkusDev`
* escalation after repeated failures
* explicit progress tracking

Until now, those rules existed only as documentation.

There was no harness machinery to:

* reduce repetitive permission prompts,
* prevent dangerous commands,
* surface missing MCP integrations,
* standardise contributor workflows,
* or provide lightweight automation around the development loop.

This created several problems:

* routine commands constantly prompted for approval,
* missing MCP configuration failed silently,
* onboarding a new session was noisy,
* and operational rules depended entirely on discipline instead of tooling.

The project needed a lightweight harness layer that improves ergonomics
without turning the repository into a heavily managed environment.

---

## Decision

Introduce a project-level Claude Code harness under `.claude/`,
combined with minimal CI and formatting infrastructure.

The harness exists to support the workflow described in `CLAUDE.md`,
not replace developer judgement.

---

## Harness Layout

```text
.claude/
├── settings.json
├── hooks/
│   ├── session-start.sh
│   ├── pre-bash-guard.sh
│   ├── post-edit-reminder.sh
│   └── stop-progress-check.sh
├── commands/
│   ├── baseline-test.md
│   └── progress.md
└── state/
```

### Responsibilities

| Path                           | Purpose                                            |
| ------------------------------ | -------------------------------------------------- |
| `settings.json`                | Shared permissions and hook wiring                 |
| `hooks/session-start.sh`       | Detect environment gaps and print onboarding hints |
| `hooks/pre-bash-guard.sh`      | Block dangerous foreground commands                |
| `hooks/post-edit-reminder.sh`  | Remind contributors to run baseline tests          |
| `hooks/stop-progress-check.sh` | Warn when edits exist without progress tracking    |
| `commands/`                    | Reusable Claude Code slash-command documentation   |
| `state/`                       | Gitignored runtime state for hooks                 |

---

## Enforcement Strategy

The harness intentionally distinguishes between:

* **dangerous actions** → blocked,
* **process discipline** → advisory only.

### Hard Enforcement

| Rule                                 | Mechanism                                |
| ------------------------------------ | ---------------------------------------- |
| No foreground `./gradlew quarkusDev` | `permissions.deny` + `pre-bash-guard.sh` |

Foreground dev-mode is considered uniquely dangerous because it can
freeze or hijack the session. The signature is deterministic and easy
to detect safely.

The guard exits with a non-zero status and explains the intended MCP path.

---

### Advisory Enforcement

| Rule                              | Mechanism                 |
| --------------------------------- | ------------------------- |
| Baseline tests before/after edits | post-edit reminder        |
| Keep progress tracked             | stop hook warning         |
| Escalate after repeated failures  | documented process only   |
| WIP = 1                           | social/process discipline |

These rules are intentionally not over-automated.

The harness philosophy is:

> enforce catastrophic mistakes, guide workflow discipline.

Over-aggressive enforcement would incentivise bypass behaviour and reduce
the usefulness of the harness.

---

## Permissions Model

The shared allowlist pre-approves only low-risk operations.

### Allowed without prompts

* read-only git:

  * `git status`
  * `git diff`
  * `git log`
  * `git show`
  * `git branch`
  * `git fetch`

* safe Gradle operations:

  * `test`
  * `check`
  * `compileJava`
  * `spotless*`
  * `build -x test`
  * `tasks`

* repository inspection:

  * `ls`
  * `find`
  * `grep`
  * `rg`

* MCP namespaces:

  * `mcp__quarkus-agent__*`

### Still requires approval

* `git add`
* `git commit`
* `git push`
* `gradle clean`
* system configuration edits
* destructive filesystem operations

The shared configuration intentionally stays conservative.

Developers can locally extend permissions through:

```text
.claude/settings.local.json
```

without changing the repository-wide defaults.

---

## MCP Registration Policy

The repository intentionally does **not** commit a `.mcp.json`.

Reasoning:

1. The transport strategy for `quarkus-agent` is not yet stable.
2. A half-correct committed configuration is worse than no configuration.
3. MCP setup may evolve into:

  * local stdio,
  * HTTP transport,
  * or Quarkus-integrated tooling later.

Instead, `session-start.sh` detects whether MCP configuration exists
in any standard location and emits a visible warning if missing.

This keeps the gap explicit instead of silently misconfigured.

---

## Formatting Strategy

Spotless `7.0.4` is wired into the build.

Current formatting rules are intentionally minimal:

* trim trailing whitespace,
* ensure newline at EOF.

Applied to:

* Java,
* Gradle Kotlin DSL,
* Markdown.

### Heavy Java formatter intentionally deferred

Google Java Format and Palantir Java Format were tested against
Temurin JDK 25 and failed due to internal javac API incompatibilities.

The project explicitly rejected:

* JDK toolchain workarounds,
* dual-JDK formatting pipelines,
* or formatter-specific CI complexity

during the bootstrap phase.

The decision follows the project's broader philosophy:

> avoid operational complexity before the codebase justifies it.

---

## CI

A minimal GitHub Actions workflow runs:

```text
spotlessCheck
test
```

on:

* pull requests,
* pushes to `main`.

Environment:

* Temurin 25

Native-image builds are intentionally deferred until later plans.

---

## Consequences

### Positive

* Routine development sessions become quieter and faster.
* Dangerous foreground dev-mode invocations are prevented automatically.
* Contributors receive immediate visibility into missing setup.
* CI catches formatting drift early.
* The harness is versioned and shared across all contributors.
* Workflow expectations become executable instead of purely documented.

### Negative

* The permission model remains intentionally conservative.
* Advisory hooks can produce noisy stderr output.
* The hooks currently assume POSIX tooling.
* Some workflow discipline still depends on human judgement.

---

## Mitigations

* Local overrides exist through `settings.local.json`.
* Hooks are intentionally tiny and individually removable.
* Advisory-only hooks reduce workflow hostility.
* Shared harness evolution happens through repository review instead of
  local drift.

---

## Philosophy

The harness is intentionally lightweight.

It is not trying to become:

* a build system,
* a policy engine,
* or an AI sandbox runtime.

Its role is narrower:

> reduce friction, prevent obvious failure modes, and reinforce the
> engineering workflow already documented in the project.

The project prioritises:

* visibility,
* operational clarity,
* and low ceremony

over maximal automation.

---

## Revisit if

* The `quarkus-agent` MCP transport becomes standardised.
* Hook noise becomes disruptive.
* A stable JDK-25-compatible Java formatter ships.
* The project gains more contributors and stricter automation becomes valuable.
* CI expands into integration, native-image, or deployment validation.
