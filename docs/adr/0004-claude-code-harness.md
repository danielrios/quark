# 0004 — Claude Code harness configuration

**Status:** Accepted, 2026-05-26
**Deciders:** Daniel + harness-engineering session
**Context refs:** [`CLAUDE.md`](../../CLAUDE.md), [`.claude/`](../../.claude/)

## Context

`CLAUDE.md` defines rigid agent operating rules — WIP=1, baseline test before
and after every change via the `quarkus-agent` MCP, no foreground
`./gradlew quarkusDev`, escalate to the human after 3 consecutive identical
test failures — but until now there was zero harness machinery to enforce
or assist with them. Every routine `git status` or `./gradlew test` invocation
prompted for permission. The `quarkus-agent` MCP, if absent, failed silently:
tool calls just errored out without surfacing that the dev-mode/test gate was
unreachable. Formatting and CI did not exist.

This left the rules as honor-system policy on the agent side and made onboarding
a new contributor (human or AI) noisy and error-prone.

## Decision

Add a project-level Claude Code harness under `.claude/` and a thin layer of
build/CI tooling that backs CLAUDE.md with enforcement and ergonomics.

### Layout

```
.claude/
├── settings.json            # permissions allow/deny + hook wiring
├── hooks/
│   ├── session-start.sh     # advisory: Java25 / gradlew / MCP detection
│   ├── pre-bash-guard.sh    # hard-block foreground quarkusDev
│   ├── post-edit-reminder.sh# advisory: remind to run baseline test
│   └── stop-progress-check.sh# advisory: warn on uncommitted edits w/o docs/progress.md update
├── commands/
│   ├── baseline-test.md     # /baseline-test
│   └── progress.md          # /progress <task>
└── state/                   # gitignored — runtime stamps for hooks
```

### Enforcement model — "block dangerous, warn on the rest"

| CLAUDE.md rule | Enforcement |
|---|---|
| No foreground `./gradlew quarkusDev` (§2 Non-Interactive) | **Hard block** via `permissions.deny` and `pre-bash-guard.sh`. Code 2 exit, stderr explains the MCP path. |
| Baseline test before/after edits (§3) | **Advisory** — `post-edit-reminder.sh` prints a reminder after every Edit/Write. Test cannot be programmatically forced without the MCP being reachable. |
| State rule (§2) — commit or `docs/progress.md` | **Advisory** — `stop-progress-check.sh` warns when edits happen with no `docs/progress.md` / commit update. Global stop-hook still enforces commit+push at session exit. |
| WIP=1 (§2) | **Not automatable** — single-file constraint is intent, not syntax. |
| 3-failure escalation (§4) | **Not automatable here** — depends on test-runner output that lives in the MCP. Future work. |

The asymmetry is deliberate: blocking foreground dev-mode prevents a class of
session-killing bash invocations that have a clear, identifiable signature.
The other rules are softer — over-enforcing them with mandatory hooks creates
friction that the agent will route around (e.g. by batching edits to avoid
reminders), which is worse than no enforcement.

### Permissions allowlist

The allowlist pre-approves only:
- read-only git (`status`, `diff`, `log`, `show`, `branch`, `fetch`),
- non-dev-mode gradle (`test`, `check`, `compileJava`, `spotless*`, `build -x test`, `tasks`),
- read-only inspection (`ls`, `find`, `rg`, `grep`),
- the `quarkus-agent` MCP namespace (`mcp__quarkus-agent__*`),
- read-only GitHub MCP calls.

Anything that writes — `git commit`, `git push`, `git add`, edits to system
config, gradle clean, etc. — still prompts. This is the floor, not the ceiling:
session-level `settings.local.json` can opt in to more without changing the
shared file.

### MCP registration is deferred

`quarkus-agent` MCP is referenced throughout `CLAUDE.md` but is not registered
in this repo. There is no `.mcp.json`. Reasons:

1. The MCP server's transport/binary is not yet specified by the project — it
   may run via `stdio` from a local install, via `http` from a user-level
   config, or be bundled into the Quarkus dev experience itself in a future
   plan.
2. Committing a half-correct `.mcp.json` would mask the gap rather than
   surface it.

Instead, `session-start.sh` detects whether `quarkus-agent` is mentioned in
any of the standard merge locations (`.mcp.json`, project/user settings) and
prints a loud warning when it is not. The first developer who actually wires
the MCP can amend this ADR with the canonical configuration.

### Dev tooling

Spotless 7.0.4 wired into `check`, so `./gradlew test` flags formatting drift.
Configured with `trimTrailingWhitespace()` and `endWithNewline()` for Java,
Gradle Kotlin DSL, and markdown — pure string-level rules with no JDK
coupling.

**Heavy Java formatter intentionally deferred.** We tried Google Java Format
1.22.0/1.24.0 and Palantir Java Format 2.50.0 in three CI rounds on Temurin
25; all three threw `NoSuchMethodError` against
`com.sun.tools.javac.util.Log$DeferredDiagnosticHandler.getDiagnostics()`.
Both formatters call into javac internals as a parser, and JDK 25 changed
that method's signature. Per CLAUDE.md §6 YAGNI: a heavy formatter on three
skeleton files is not worth a CI workaround (e.g. running Spotless under
a separate JDK 21 toolchain). Revisit once a JDK-25-stable version of either
formatter ships.

### CI

A single `.github/workflows/ci.yml` runs `spotlessCheck` and `test` on
Temurin 25 for every PR and push to `main`. No native-image build yet —
that is plan-7 territory per ADR 0003.

## Consequences

### Positive
- Routine commands no longer prompt. Agent sessions feel quieter and faster.
- The one truly dangerous bash invocation (`quarkusDev` in foreground) is now
  prevented at the harness layer, not at the agent's discretion.
- Onboarding a new session shows the current `docs/progress.md` task and surfaces
  any setup gaps in the first 10 lines of output.
- CI catches formatting drift before review.
- `.claude/settings.json`, `hooks/`, and `commands/` are tracked in git, so
  every contributor (human or AI) gets the same harness. `.claude/state/`
  and `.claude/settings.local.json` are local-only.

### Negative
- The allowlist is conservative. The agent will still prompt for many normal
  operations (any `git` write, gradle clean, etc.). Tuning is expected.
- Hook scripts are bash and depend on `grep`/`sed`/`date` being POSIX-ish.
  Windows agents would need WSL or a rewrite. Acceptable for this project.
- The advisory hooks (post-edit, stop) print to stderr, which mingles with
  real tool errors. Tolerable at current volume; revisit if it becomes noisy.

### Mitigations
- `.claude/settings.local.json` lets each developer opt into more
  permissions for their own sessions without changing shared config.
- The hooks are tiny (~30 lines each) and easy to disable individually by
  removing their entry from `settings.json`.

## Revisit if

- The `quarkus-agent` MCP transport is decided — register it in `.mcp.json`
  and update `session-start.sh` to do a real reachability check.
- A second formatter or linter becomes worthwhile (e.g. ArchUnit enforcement
  in plan 7 — that runs as a test, not via Spotless).
- A JDK-25-stable release of Google Java Format or Palantir Java Format ships.
  Add it back to the `spotless { java { ... } }` block in `build.gradle.kts`
  and run `./gradlew spotlessApply` once to bring the existing source in line.
  The Spotless config already targets `src/**/*.java` — only the formatter
  step itself is missing.
- Hook noise becomes a problem. Consider moving from per-event stderr prints
  to a single `.claude/state/session.log` that the agent can `Read` on demand.
- The allowlist needs expansion as the project grows. Add new patterns here,
  not in `settings.local.json` — shared harness should evolve with the team.
