# Spec — API mock for testing the loop

**Status**: design, awaiting implementation plan
**Date**: 2026-06-26
**Project**: `quark` (orchestration tooling — `scripts/`, **not** `src/main/java`)
**Source brainstorm**: [2026-06-26-api-mock-for-testing-the-loop](../brainstorms/2026-06-26-api-mock-for-testing-the-loop.md)
**Context**: [ADR 0007](../../adr/0007-multi-model-loop-harness.md), [`scripts/orchestrate.sh`](../../../scripts/orchestrate.sh), [MANIFESTO.md](../../../MANIFESTO.md)

---

# 1. Goal

Drive every control-flow branch of `scripts/orchestrate.sh` — happy path, 3-fail escalation, 5-fail circuit breaker, `--skip-to`, lock contention, marker-driven completion — **deterministically and for free**, by replacing the real `claude` invocation with a scripted, phase-aware mock behind an explicit, opt-in seam.

---

# 2. Non-goals

This feature explicitly does **not**:

* Mock the Anthropic HTTP API or set `ANTHROPIC_BASE_URL` (brainstorm Approach C — a separately-justified *future* CLI/MCP smoke test, not this work).
* Test that the real `claude` CLI, MCP wiring (`quarkus-agent`, `context7`), or slash-command rendering work end-to-end. Loop **logic** only.
* Assert on the *content* the agent would produce — the loop never inspects a response body, only exit codes and the `IMPL_COMPLETE` marker.
* Change any loop **behaviour**. The seam is backward-compatible: with `CLAUDE_BIN` unset, production runs the real `claude` byte-for-byte as today.
* Touch `src/main/java` or the Quarkus application in any way.
* Introduce a new test-runner dependency (`bats` is **not** installed; we use plain `bash` — see §6).

---

# 3. Design

## 3.1 The seam (one-line, additive)

`scripts/orchestrate.sh` calls `claude` at exactly one site (`run_phase()`, line 116). Change only that token:

```diff
-  claude -p "$prompt" \
+  "${CLAUDE_BIN:-claude}" -p "$prompt" \
```

`CLAUDE_BIN` unset → resolves to `claude` → production unchanged. Tests set `CLAUDE_BIN=/abs/path/to/mock-claude.sh`. The seam is one greppable variable; the script self-documents that it is mockable (MANIFESTO: explicit > magical, additive evolution). This is the **only** edit to `orchestrate.sh`.

## 3.2 The control-flow contract (verified, narrower than the brainstorm)

`orchestrate.sh` performs exactly **two** filesystem existence checks (verified by `grep -nE '\[\[ +-[fed]' scripts/orchestrate.sh`):

| Line | Check | Role |
|------|-------|------|
| 82 | `[[ -f "$LOCK_FILE" ]]` | lock contention (input — written by the test, not the mock) |
| 198 | `[[ -f ".claude/state/IMPL_COMPLETE" ]]` | breaks the implement loop |

The spec/plan/review file paths (`docs/superpowers/specs/${DATE}-${SLUG}.md`, line 169/244/252) are passed to `claude` as **string arguments**; the script never `-f`-checks them. The mock does not read its inputs. Therefore:

> **Mandatory mock contract: per-invocation exit code + the `IMPL_COMPLETE` marker.**
> Creating spec/plan/review artifacts is **optional** assertion fidelity, **not** required for the phase chain to proceed (this corrects the brainstorm §1 bullet list, which overstated the dependency).

To keep the mock minimal (YAGNI), it does **not** create spec/plan/review artifacts. Assertions key off the invocation log (§3.4), not artifact paths.

## 3.3 The mock — `scripts/test/mock-claude.sh`

A phase-aware, sequence-driven stub. Responsibilities, in order:

1. **Leak guard (risk 3).** Refuse to run unless `MOCK_CLAUDE=1` is set in the environment:
   ```
   [[ "${MOCK_CLAUDE:-}" == "1" ]] || { echo "mock-claude: refusing to run without MOCK_CLAUDE=1" >&2; exit 99; }
   ```
   This makes it impossible for the mock to silently service a real run, even if `CLAUDE_BIN` leaks — acute under `--dangerously-skip-permissions`.

2. **Parse the phase.** Extract the `/<command>` token from the `-p "$prompt"` argument and the `--model <model>` value. The command token (`brainstorm|spec|plan|implement|advisor|review|simplify|handoff`) is the stable phase key (risk 4: prefer the command token over hard-coded indices).

3. **Advance the invocation counter.** Maintain a call-count state file `.claude/state/mock-claude.calls` (relative → resolves inside the per-scenario sandbox cwd, §3.5). Increment on every call. `mkdir -p .claude/state` first.

4. **Append to the invocation log (§3.4).**

5. **Apply the fixture for this invocation** (§3.6): determine this call's exit code and whether to `touch .claude/state/IMPL_COMPLETE`.

6. **`/handoff` default branch.** `handoff` (line 261, called with `|| true`) has no fixture entry by default → exit 0, no marker. The mock must have a safe default for any command not in the fixture: exit 0, no side effect.

## 3.4 Invocation log — the primary assertion surface (advisor #3)

The mock appends one line per call to `$MOCK_CLAUDE_LOG`:

```
<command>|<model>|<exit_code>
```

e.g.

```
brainstorm|opus|0
spec|opus|0
plan|opus|0
implement|sonnet|1
implement|sonnet|1
implement|sonnet|1
advisor|opus|0
review|opus|0
simplify|sonnet|0
handoff|sonnet|0
```

Tests assert on this sequence — **not** on orchestrate's colored banner output (brittle) and **not** on dated artifact paths (couples to the `DATE`+slug computation and its midnight-boundary risk). The log proves *what was invoked, with which model, and how it exited*, which is exactly the loop's observable behaviour.

## 3.5 Test isolation — throwaway cwd per scenario (advisor #1, must-fix)

Every path in `orchestrate.sh` is **relative** (`LOG_DIR`, `LOCK_FILE`, the `docs/superpowers/...` mkdirs, the `IMPL_COMPLETE` check). Running tests against the live tree would litter real dated artifacts, write into the shared `.claude/state/`, and (lock scenario) leave a lock that blocks a real run.

**Each scenario runs in a fresh temp dir:**

```
TMPDIR_SCENARIO="$(mktemp -d)"
( cd "$TMPDIR_SCENARIO" && MOCK_CLAUDE=1 \
    CLAUDE_BIN="$REPO/scripts/test/mock-claude.sh" \
    MOCK_CLAUDE_LOG="$TMPDIR_SCENARIO/invocations.log" \
    MOCK_FIXTURE="..." \
    SLEEP_BETWEEN=0 \
    "$REPO/scripts/orchestrate.sh" --feature "test feature" [args] )
# assert, then:
rm -rf "$TMPDIR_SCENARIO"
```

The mock inherits this cwd (invoked via absolute `CLAUDE_BIN`), so its `IMPL_COMPLETE` touch and orchestrate's line-198 check both resolve into the sandbox. Hermetic and re-runnable. `SLEEP_BETWEEN=0` removes the cooldown so the suite is fast.

## 3.6 Fixture format

The fixture sequences exit codes (and the completion marker) across the *implement-loop* invocations, which is the only place sequencing matters (same `/implement` command called repeatedly). Simplest sufficient format: a newline-delimited list in an env var `MOCK_FIXTURE`, one entry per implement/advisor invocation, consumed by call index *within the implement loop*:

```
<exit_code>[:complete]
```

* `0` — succeed, no marker (implement made progress but isn't done; loop continues).
* `1` — fail (drives `consecutive_failures` / advisor escalation).
* `0:complete` — succeed **and** `touch IMPL_COMPLETE` (breaks the loop).

Non-implement phases (brainstorm/spec/plan/review/simplify/handoff) default to exit 0 with no marker and need no fixture entry — they run once each and the happy path wants them green. A scenario that needs e.g. a failing spec phase can extend the fixture keying, but no current scenario does (YAGNI).

Using `--skip-to implement` for the escalation/breaker scenarios (advisor #5) means the fixture's first entry maps to the **first implement call** with no brainstorm/spec/plan preamble to offset the counter.

## 3.7 New files / changes summary

| File | Change |
|------|--------|
| `scripts/orchestrate.sh` | **Edit** line 116: `claude` → `"${CLAUDE_BIN:-claude}"` (only change) |
| `scripts/test/mock-claude.sh` | **New** — phase-aware stub (§3.3), executable |
| `scripts/test/test-orchestrate.sh` | **New** — plain-bash scenario suite (§5), executable |

No package-layout impact (this is `scripts/`, not `com.quark`). The seam keeps the eventual refactor clean: if `orchestrate.sh` is ever ported into a richer harness, `CLAUDE_BIN` is the documented extraction point for the model-invocation boundary (CLAUDE.md §7 spirit, applied at the tooling layer).

---

# 4. API surface / interfaces

The "API" here is the contract between `orchestrate.sh`, the mock, and the test, expressed as environment variables:

| Variable | Set by | Read by | Meaning |
|----------|--------|---------|---------|
| `CLAUDE_BIN` | test | `orchestrate.sh:116` | path to the binary the loop invokes; defaults to `claude` |
| `MOCK_CLAUDE` | test | `mock-claude.sh` | must be `1` or the mock refuses (leak guard) |
| `MOCK_CLAUDE_LOG` | test | `mock-claude.sh` | absolute path the mock appends `cmd|model|exit` lines to |
| `MOCK_FIXTURE` | test | `mock-claude.sh` | newline list of `exit[:complete]` per implement-loop call |
| `SLEEP_BETWEEN` | test | `orchestrate.sh` (existing) | set to `0` to remove cooldown |

**Mock CLI contract** (so it is a drop-in for `claude`): accepts and ignores `-p <prompt>`, `--model <model>`, `--dangerously-skip-permissions`, `--verbose`; reads the phase from the `-p` value; exits with the fixture-driven code; reserved exit `99` = leak-guard refusal (never a fixture value).

**Invocation-log line grammar:** `^<command>\|<model>\|<exit_code>$`, one per call, append order = invocation order.

---

# 5. Test strategy

The deliverable *is* a test, plus the suite that proves it. Six scenarios, each in an isolated cwd (§3.5), each with an explicit terminal state and expected exit code (advisor #4):

| # | Scenario | Invocation / args | Terminal assertion |
|---|----------|-------------------|--------------------|
| 1 | **Happy path** | full run (no `--skip-to`); fixture `0:complete` (implement completes first try) | orchestrate exits `0`; log sequence = `brainstorm,spec,plan,implement,review,simplify,handoff` with correct models (`opus` for brainstorm/spec/plan/review, `sonnet` for implement/simplify/handoff) |
| 2 | **Marker-driven completion** | `--skip-to implement`; fixture `0`,`0`,`0:complete` | exits `0`; exactly 3 `implement` lines in log; no `advisor` line; loop broke on marker, not iteration cap |
| 3 | **Escalate-at-3** | `--skip-to implement`; fixture `1`,`1`,`1`,`0:complete` | exits `0`; log shows 3 `implement` fails then an `advisor|opus` success; "Escalating to Opus advisor" banner present; advisor touched the marker → completion |
| 4 | **Break-at-5 (circuit breaker)** | `--skip-to implement`; fixture `1`,`1`,`1`,`1`,`1` | exits `1`; log = 3 `implement` + 2 `advisor` (5 total) fails; "CIRCUIT BREAKER" banner present; no marker ever created |
| 5 | **`--skip-to`** | `--skip-to implement`; fixture `0:complete` | log has **no** `brainstorm`/`spec`/`plan` lines (skipped); first line is `implement`; exits `0` |
| 6 | **Lock contention** | pre-write `.claude/state/orchestrate.lock` with the test shell's **own live** `$$`; any fixture | orchestrate exits `1`; "already running" message; **no** `claude`/mock invocation (empty log); test owns lock cleanup — abort at line 85 fires **before** the `trap` at line 90, so orchestrate does **not** remove the lock |

Suite harness: a `pass`/`fail` counter, `assert_eq` / `assert_contains` / `assert_log_seq` helpers, `set -u`, per-scenario `mktemp -d` + `rm -rf` teardown via `trap`. Exit non-zero if any scenario fails.

### Relationship to CLAUDE.md §3

This feature lives in `scripts/` + a new shell test; it does **not** touch `src/main/java`. Two things are both true and must both be stated (advisor #6):

* **The shell suite (`scripts/test/test-orchestrate.sh`) is the authority** that this feature works. A green run across all six scenarios is the victory condition.
* **CLAUDE.md §3's Java gate is still run and recorded** — the contract is unconditional. It certifies *nothing* about this feature (there is no Java code to exercise; the result is the existing 26-test baseline, unchanged), but "the Java gate is silent here" means *it proves nothing*, **not** *skip it*. Record both results in `docs/progress.md`.

---

# 6. Rollback plan

Fully additive and reversible:

* **The seam** is the only production-touching change. To revert: restore line 116 to `claude -p "$prompt"`. With `CLAUDE_BIN` unset the line is already a behavioural no-op, so the blast radius is zero even before reverting.
* **The mock and test** are new files under `scripts/test/`. To revert: `git rm scripts/test/mock-claude.sh scripts/test/test-orchestrate.sh`. Nothing else references them.
* **No state migration, no schema, no dependency.** `bats` is deliberately not adopted, so there is nothing to uninstall. If a future decision adopts `bats`, the plain-bash suite is the throwaway scaffold.
* **CI** (if wired — see §7) is removed by deleting the added job/step from `.github/workflows/ci.yml`.

---

# 7. Open questions

1. **CI wiring.** Should `scripts/test/test-orchestrate.sh` run in `.github/workflows/ci.yml` (a real, fast, dependency-free gate that protects `orchestrate.sh` from regression), or stay a local/manual tool? Recommendation: wire it — it is seconds-fast and token-free — but that is a human call and a separate, small change.
2. **Mock-drift smoke (risk 2).** The mock asserts a *fiction* if the real `claude` side-effect contract changes (e.g. the marker path moves). Do we want one periodic real-`claude` smoke (overlapping brainstorm Approach C's legitimate use) to confirm the contract still holds, and if so, on what cadence? Out of scope for this spec; flagged for a follow-up.
3. **Fixture for non-implement phase failures.** No current scenario needs a failing brainstorm/spec/plan/review phase, so the fixture only sequences the implement loop (§3.6). If a future scenario needs, say, "spec phase fails → abort," the fixture format gains a per-command keying. Deferred under YAGNI; noting the extension point.
