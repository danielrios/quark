# Plan — API mock for testing the loop (`CLAUDE_BIN` seam)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drive every control-flow branch of `scripts/orchestrate.sh` — happy path, marker-driven completion, 3-fail escalation, 5-fail circuit breaker, `--skip-to`, lock contention — **deterministically and for free**, by replacing the real `claude` invocation with a scripted, phase-aware mock behind an explicit, opt-in `CLAUDE_BIN` seam.

**Spec:** [docs/superpowers/specs/2026-06-26-api-mock-for-testing-the-loop.md](../specs/2026-06-26-api-mock-for-testing-the-loop.md)
**Context:** [ADR 0007](../../adr/0007-multi-model-loop-harness.md), [MANIFESTO.md](../../../MANIFESTO.md)

---

## ⚠️ Read first: which gate certifies this feature

This work lives **entirely in `scripts/`** and a new shell test. It does **not** touch `src/main/java` or the Quarkus app.

- **Authority gate = the shell suite `scripts/test/test-orchestrate.sh`.** A green run across all six scenarios is the victory condition for every implementation task here. Each task's "Definition of done" is a **shell assertion**, not `./gradlew test`.
- **CLAUDE.md §3's Java gate is still run and recorded — but it is silent here.** It exercises zero lines of this feature; its result is the unchanged 26-test baseline. "Silent" means *it proves nothing about this feature*, **not** *skip it*. Run it once at Task 0 (pre-state) and once at close-out (Task 7), record both in `docs/progress.md`. Do **not** inherit it as the per-task DoD — doing so is exactly the "wrong gate inherited" failure in spec §5 / risk #1.

---

## The fixture-consumption model (settled — implement exactly this)

`MOCK_FIXTURE` is a newline-delimited queue of `<exit>[:complete]` entries. The queue is consumed **only** by `implement` and `advisor` command invocations — i.e. by each iteration of orchestrate's implement loop. It is **never** consumed by `brainstorm`, `spec`, `plan`, `review`, `simplify`, or `handoff`; those always exit `0`, touch no marker, and do **not** advance the fixture index.

Why this and not a global call counter: spec Scenario 1 runs the full chain with fixture `0:complete` and expects completion on the **first** `implement` call. A global counter would consume that lone entry during `brainstorm`. Spec Scenario 3 (`1,1,1,0:complete`) expects the 4th entry to be the **advisor's** marker-touch, so `advisor` consumes too. Only an implement/advisor-scoped queue satisfies both. This deliberately overrides spec §3.3 step 3's "increment on every call" wording; the §3.6 "offset" prose is reconciled by there being **no offset at all** under this model. Do not reintroduce a global counter to honour that sentence.

State file for the index: `.claude/state/mock-claude.calls` (relative → resolves inside the per-scenario sandbox cwd). Advance it only on `implement`/`advisor`.

---

## File Map

| File | Status | Responsibility |
|------|--------|----------------|
| `scripts/orchestrate.sh` | Modify | One-line seam at line 116: `claude` → `"${CLAUDE_BIN:-claude}"` |
| `scripts/test/mock-claude.sh` | **Create** | Phase-aware stub: leak guard, parse `/<cmd>` + `--model`, fixture-driven exit + `IMPL_COMPLETE` marker, invocation log |
| `scripts/test/test-orchestrate.sh` | **Create** | Plain-bash scenario suite: helpers + 6 isolated scenarios |
| `docs/progress.md` | Modify | Record both gates (shell authority + silent Java baseline) |
| `docs/adr/0007-multi-model-loop-harness.md` | Modify (optional) | Cross-link the seam + test as the loop's regression guard |

---

## Task 0: Scaffolding + baseline gate `(S)`

**Files:**
- Create: `scripts/test/` directory
- Create (skeleton, executable): `scripts/test/mock-claude.sh`, `scripts/test/test-orchestrate.sh`

- [ ] **Step 0.1: Record the pre-state Java baseline (CLAUDE.md §3).**

  Run the test gate and record the result — this certifies the existing app is green before any change, and documents that the Java gate is *silent on this feature* (no Java touched). Use the project's working gate:

  ```bash
  ./gradlew test --rerun-tasks
  ```

  Expected: `BUILD SUCCESSFUL`, 26 tests, 0 failures. (Per `docs/progress.md`, the MCP `devui-testing_runTests` port-detection bug means `./gradlew test` is the active gate fallback — harness pre-approved.)

- [ ] **Step 0.2: Create the directory and executable skeletons.**

  ```bash
  mkdir -p scripts/test
  ```

  Create `scripts/test/mock-claude.sh`:

  ```bash
  #!/usr/bin/env bash
  set -euo pipefail
  # mock-claude.sh — scripted, phase-aware stand-in for `claude`, behind CLAUDE_BIN.
  # Implemented in Task 2. Skeleton only.
  echo "mock-claude: not yet implemented" >&2
  exit 1
  ```

  Create `scripts/test/test-orchestrate.sh`:

  ```bash
  #!/usr/bin/env bash
  set -u
  # test-orchestrate.sh — plain-bash scenario suite for orchestrate.sh control flow.
  # Implemented in Tasks 3–6. Skeleton only.
  echo "test-orchestrate: not yet implemented" >&2
  exit 1
  ```

- [ ] **Step 0.3: Make both executable.**

  `orchestrate.sh` invokes `CLAUDE_BIN` as an absolute path; a non-executable mock fails silently. Both scripts must be `+x`.

  ```bash
  chmod +x scripts/test/mock-claude.sh scripts/test/test-orchestrate.sh
  ```

- [ ] **Step 0.4: Verify the skeletons exist and are executable.**

  **Definition of done:**
  ```bash
  test -x scripts/test/mock-claude.sh && test -x scripts/test/test-orchestrate.sh && echo OK
  ```
  Expected: `OK`.

- [ ] **Step 0.5: Commit.**

  ```bash
  git add scripts/test/mock-claude.sh scripts/test/test-orchestrate.sh
  git commit -m "test(orchestrate): scaffold mock-claude + scenario suite skeletons"
  ```

---

## Task 1: The `CLAUDE_BIN` seam in `orchestrate.sh` `(S)`

**Files:** Modify `scripts/orchestrate.sh` (line 116 only).

The **only** production-touching change. Backward-compatible: with `CLAUDE_BIN` unset it resolves to `claude` and the line is a byte-for-byte no-op.

- [ ] **Step 1.1: Apply the one-token edit.**

  In `run_phase()`, change:
  ```diff
  -  claude -p "$prompt" \
  +  "${CLAUDE_BIN:-claude}" -p "$prompt" \
  ```
  This is the single edit to `orchestrate.sh`. Do not touch any other line.

- [ ] **Step 1.2: Verify production is unchanged (seam defaults to `claude`).**

  **Definition of done:** the script still parses and the seam resolves to the real binary when unset:
  ```bash
  bash -n scripts/orchestrate.sh && echo "syntax OK"
  grep -n '"${CLAUDE_BIN:-claude}"' scripts/orchestrate.sh
  ```
  Expected: `syntax OK`, and exactly one match at the former `claude -p` site. With `CLAUDE_BIN` unset the loop invokes `claude` exactly as before.

- [ ] **Step 1.3: Commit.**

  ```bash
  git add scripts/orchestrate.sh
  git commit -m "feat(orchestrate): add explicit CLAUDE_BIN seam at the claude call site"
  ```

---

## Task 2: Implement `mock-claude.sh` `(M)`

**Files:** Modify `scripts/test/mock-claude.sh` (replace the skeleton with the full stub).

Responsibilities, in order (spec §3.3):

1. **Leak guard (risk 3).** Refuse unless `MOCK_CLAUDE=1`:
   ```bash
   [[ "${MOCK_CLAUDE:-}" == "1" ]] || { echo "mock-claude: refusing to run without MOCK_CLAUDE=1" >&2; exit 99; }
   ```
   Exit `99` is reserved for this refusal and is **never** a fixture value.

2. **Parse args (drop-in for `claude`).** Accept and ignore `-p <prompt>`, `--model <model>`, `--dangerously-skip-permissions`, `--verbose`. Extract the `/<command>` token from the `-p` value (regex on the first `/<word>`) → one of `brainstorm|spec|plan|implement|advisor|review|simplify|handoff`. Extract the `--model` value. Default model to empty string if absent.

3. **Append the invocation log line.** If `MOCK_CLAUDE_LOG` is set, append `<command>|<model>|<exit_code>` (one line per call, append order = invocation order). The exit code is computed in step 4 before the line is written.

4. **Compute this call's behaviour:**
   - If command is `implement` or `advisor`: `mkdir -p .claude/state`; read+increment the fixture index in `.claude/state/mock-claude.calls`; take the Nth (1-based) entry of `MOCK_FIXTURE` (newline-split). Entry grammar `<exit>[:complete]`:
     - `0` → exit 0, no marker.
     - `1` → exit 1, no marker.
     - `0:complete` → `touch .claude/state/IMPL_COMPLETE`, exit 0.
     - Past the end of the fixture → default exit 0, no marker (so trailing phases after a break stay green).
   - Any **other** command (`brainstorm|spec|plan|review|simplify|handoff` or unknown) → exit 0, no marker, **do not** advance the fixture index (the `/handoff` default branch of spec §3.3 step 6 and the non-implement default of §3.2).

5. **Exit with the computed code.**

- [ ] **Step 2.1: Write the full mock.** Implement steps 1–5 above. Keep it minimal (YAGNI): it does **not** read its inputs and does **not** create spec/plan/review artifacts — assertions key off the invocation log, not artifact paths (spec §3.2).

- [ ] **Step 2.2: Verify the leak guard.**

  **Definition of done:**
  ```bash
  MOCK_CLAUDE= scripts/test/mock-claude.sh -p "/implement x" --model sonnet; echo "exit=$?"
  ```
  Expected: refusal message on stderr, `exit=99`.

- [ ] **Step 2.3: Verify implement consumes the fixture, touches the marker, and logs (run in a throwaway dir).**

  **Definition of done** — drive the mock directly through one fixture in an isolated cwd:
  ```bash
  T=$(mktemp -d); ( cd "$T" \
    && MOCK_CLAUDE=1 MOCK_CLAUDE_LOG="$T/log" MOCK_FIXTURE=$'1\n0:complete' \
       "$OLDPWD/scripts/test/mock-claude.sh" -p "/implement plan.md" --model sonnet; echo "call1=$?" \
    && MOCK_CLAUDE=1 MOCK_CLAUDE_LOG="$T/log" MOCK_FIXTURE=$'1\n0:complete' \
       "$OLDPWD/scripts/test/mock-claude.sh" -p "/implement plan.md" --model sonnet; echo "call2=$?" \
    && test -f .claude/state/IMPL_COMPLETE && echo "marker OK" \
    && cat "$T/log" ); rm -rf "$T"
  ```
  Expected: `call1=1`, `call2=0`, `marker OK`, and the log is exactly:
  ```
  implement|sonnet|1
  implement|sonnet|0
  ```
  (Second call took fixture entry 2 = `0:complete` → marker + exit 0.)

- [ ] **Step 2.4: Verify a non-implement command exits 0 and does not consume the fixture.**

  **Definition of done:**
  ```bash
  T=$(mktemp -d); ( cd "$T" \
    && MOCK_CLAUDE=1 MOCK_CLAUDE_LOG="$T/log" MOCK_FIXTURE=$'1' \
       "$OLDPWD/scripts/test/mock-claude.sh" -p "/brainstorm feature" --model opus; echo "exit=$?" \
    && test ! -f .claude/state/mock-claude.calls && echo "index not advanced" \
    && cat "$T/log" ); rm -rf "$T"
  ```
  Expected: `exit=0`, `index not advanced`, log line `brainstorm|opus|0`. (The `1` fixture entry was **not** consumed because `brainstorm` never touches the queue.)

- [ ] **Step 2.5: Commit.**

  ```bash
  git add scripts/test/mock-claude.sh
  git commit -m "test(orchestrate): implement phase-aware mock-claude (leak guard, fixture queue, marker, log)"
  ```

---

## Task 3: Test harness + helpers + Scenario 1 (happy path) `(M)`

**Files:** Modify `scripts/test/test-orchestrate.sh` (replace skeleton with the harness and the first scenario).

This task establishes the suite's spine; later tasks only add scenarios.

- [ ] **Step 3.1: Build the harness.** Implement, at minimum:
  - `set -u`; `REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"` (absolute repo root).
  - `PASS=0 FAIL=0` counters; `pass "<msg>"` / `fail "<msg>"` helpers that increment and print.
  - **`run_scenario`** helper that: creates `TMPDIR_SCENARIO="$(mktemp -d)"`, runs `orchestrate.sh` from inside it with `set +e` capturing the exit code, and exposes `$SCN_EXIT` and `$SCN_LOG` (path to `invocations.log`) to the caller. Each scenario passes its own args/fixture. Invocation template (spec §3.5):
    ```bash
    ( cd "$TMPDIR_SCENARIO" && MOCK_CLAUDE=1 \
        CLAUDE_BIN="$REPO/scripts/test/mock-claude.sh" \
        MOCK_CLAUDE_LOG="$TMPDIR_SCENARIO/invocations.log" \
        MOCK_FIXTURE="$fixture" \
        SLEEP_BETWEEN=0 \
        "$REPO/scripts/orchestrate.sh" --feature "test feature" "$@" )
    ```
    `SLEEP_BETWEEN=0` removes the cooldown so the suite is fast.
  - Per-scenario teardown: `rm -rf "$TMPDIR_SCENARIO"` (a `trap` on the temp dir is acceptable).
  - **Assertion helpers** (note the *altitude* each scenario needs — exact-match alone is insufficient):
    - `assert_eq <actual> <expected> <msg>` — scalar equality (exit codes, counts).
    - `assert_contains <file> <substring> <msg>` — banner / message presence (reads orchestrate stdout captured per scenario, or the log).
    - `assert_log_seq <logfile> <comma-or-newline-expected> <msg>` — **exact** full-sequence match (Scenario 1 only).
    - `assert_log_count <logfile> <command> <n> <msg>` — count lines whose command field == `<command>`.
    - `assert_log_absent <logfile> <command> <msg>` — assert **no** line has that command.
    - `assert_log_first <logfile> <command> <msg>` — first line's command field == `<command>`.
    - Empty/absent-log tolerance: helpers must treat a non-existent logfile as zero lines (Scenario 6 never invokes the mock).
  - At end of file: print `PASS/FAIL` totals; `exit 1` if `FAIL > 0`, else `exit 0`.

  > To assert on banners (Scenarios 3, 4, 6) the harness must capture orchestrate's **stdout** too. Have `run_scenario` redirect combined output to `$TMPDIR_SCENARIO/stdout.log` and expose `$SCN_OUT`. Banners contain ANSI color codes — match on the plain substring (e.g. `CIRCUIT BREAKER`, `Escalating to Opus advisor`, `already running`), not the full colored line.

- [ ] **Step 3.2: Scenario 1 — happy path.**
  - Args: none beyond `--feature` (full run, no `--skip-to`).
  - Fixture: `0:complete` (first `implement` completes first try).
  - Assertions (spec §5 row 1):
    - `assert_eq "$SCN_EXIT" 0` — orchestrate exits 0.
    - `assert_log_seq "$SCN_LOG"` **exactly**:
      ```
      brainstorm|opus|0
      spec|opus|0
      plan|opus|0
      implement|sonnet|0
      review|opus|0
      simplify|sonnet|0
      handoff|sonnet|0
      ```
      (Models: `opus` for brainstorm/spec/plan/review; `sonnet` for implement/simplify/handoff — per orchestrate's phase→model routing. The `implement` line is `0` because `0:complete` exits 0 and the marker breaks the loop before any failure handling.)

- [ ] **Step 3.3: Run the suite — Scenario 1 must pass.**

  **Definition of done:**
  ```bash
  scripts/test/test-orchestrate.sh; echo "suite exit=$?"
  ```
  Expected: Scenario 1 reported PASS; `suite exit=0`.

- [ ] **Step 3.4: Commit.**

  ```bash
  git add scripts/test/test-orchestrate.sh
  git commit -m "test(orchestrate): scenario suite harness + happy-path scenario (green)"
  ```

---

## Task 4: Scenarios 2 & 5 — marker-driven completion + `--skip-to` `(S)`

**Files:** Modify `scripts/test/test-orchestrate.sh` (append two scenarios).

Both use `--skip-to implement`. `--skip-to` skips only phases **before** `implement`; `review`/`simplify`/`handoff` still run **after** the loop breaks — so assert on counts/presence/absence, **not** exact full-sequence equality.

- [ ] **Step 4.1: Scenario 2 — marker-driven completion.**
  - Args: `--skip-to implement`. Fixture: `0`, `0`, `0:complete` (three newline entries).
  - Assertions (spec §5 row 2):
    - `assert_eq "$SCN_EXIT" 0`.
    - `assert_log_count "$SCN_LOG" implement 3` — exactly three `implement` lines (the loop broke on the marker at the 3rd call, **not** the iteration cap).
    - `assert_log_absent "$SCN_LOG" advisor` — no escalation occurred.

- [ ] **Step 4.2: Scenario 5 — `--skip-to`.**
  - Args: `--skip-to implement`. Fixture: `0:complete`.
  - Assertions (spec §5 row 5):
    - `assert_eq "$SCN_EXIT" 0`.
    - `assert_log_absent "$SCN_LOG" brainstorm`, `assert_log_absent "$SCN_LOG" spec`, `assert_log_absent "$SCN_LOG" plan` — pre-implement phases were skipped.
    - `assert_log_first "$SCN_LOG" implement` — first line is `implement`.

- [ ] **Step 4.3: Run the suite — Scenarios 1, 2, 5 pass.**

  **Definition of done:**
  ```bash
  scripts/test/test-orchestrate.sh; echo "suite exit=$?"
  ```
  Expected: 3 scenarios PASS, 0 FAIL; `suite exit=0`.

- [ ] **Step 4.4: Commit.**

  ```bash
  git add scripts/test/test-orchestrate.sh
  git commit -m "test(orchestrate): scenarios 2 (marker completion) + 5 (--skip-to)"
  ```

---

## Task 5: Scenarios 3 & 4 — escalation + circuit breaker `(M)`

**Files:** Modify `scripts/test/test-orchestrate.sh` (append two scenarios).

These exercise the heart of the loop: `consecutive_failures` → Opus advisor after 3 Sonnet fails → circuit breaker after 2 more (5 total). Both use `--skip-to implement` so the fixture's first entry maps to the first `implement` call.

Traced control flow (orchestrate lines 186–238): the marker check (line 198) runs **before** failure handling, so a `0:complete` on an advisor call completes the run; a 5th failure trips `CIRCUIT BREAKER` and `exit 1` **inside** the loop (no trailing review/simplify/handoff).

- [ ] **Step 5.1: Scenario 3 — escalate-at-3.**
  - Args: `--skip-to implement`. Fixture: `1`, `1`, `1`, `0:complete`.
  - Trace: implement fail ×3 → escalate → advisor call takes entry 4 (`0:complete`) → touches marker → loop breaks → review/simplify/handoff run.
  - Assertions (spec §5 row 3) — use subsequence/prefix + presence, **not** exact equality:
    - `assert_eq "$SCN_EXIT" 0`.
    - `assert_log_count "$SCN_LOG" implement 3` and `assert_log_count "$SCN_LOG" advisor 1`.
    - The three `implement` lines are `implement|sonnet|1` and the advisor line is `advisor|opus|0` (verify the model field: advisor runs on `opus`).
    - `assert_contains "$SCN_OUT" "Escalating to Opus advisor"` — escalation banner present.

- [ ] **Step 5.2: Scenario 4 — break-at-5 (circuit breaker).**
  - Args: `--skip-to implement`. Fixture: `1`, `1`, `1`, `1`, `1`.
  - Trace: implement fail ×3 → escalate → advisor fail (attempt 1/2) → advisor fail (attempt 2/2) → `CIRCUIT BREAKER`, `exit 1`. No marker ever created; no trailing phases.
  - Assertions (spec §5 row 4):
    - `assert_eq "$SCN_EXIT" 1`.
    - `assert_log_count "$SCN_LOG" implement 3` and `assert_log_count "$SCN_LOG" advisor 2` (5 total invocations, all exit 1).
    - `assert_contains "$SCN_OUT" "CIRCUIT BREAKER"` — breaker banner present.
    - `assert_log_absent "$SCN_LOG" review` — the loop exited before reaching the review phase.

- [ ] **Step 5.3: Run the suite — Scenarios 1–5 pass.**

  **Definition of done:**
  ```bash
  scripts/test/test-orchestrate.sh; echo "suite exit=$?"
  ```
  Expected: 5 scenarios PASS, 0 FAIL; `suite exit=0`.

- [ ] **Step 5.4: Commit.**

  ```bash
  git add scripts/test/test-orchestrate.sh
  git commit -m "test(orchestrate): scenarios 3 (escalate-at-3) + 4 (circuit breaker)"
  ```

---

## Task 6: Scenario 6 (lock contention) + full green suite `(S)`

**Files:** Modify `scripts/test/test-orchestrate.sh` (append the final scenario). This task is also the **end-to-end verification**: a single green run of all six scenarios is the feature's victory condition.

- [ ] **Step 6.1: Scenario 6 — lock contention.**
  - Setup: inside the scenario's temp dir, pre-create `.claude/state/orchestrate.lock` containing the **test shell's own live PID** (`$$`). Because that PID is alive, orchestrate's line-82 `[[ -f LOCK_FILE ]]` + `kill -0 "$pid"` check (line 84) sees a live owner and aborts at line 85 — **before** the `trap '… EXIT'` is armed at line 90, so orchestrate does **not** remove the lock (the test owns cleanup).
    ```bash
    mkdir -p "$TMPDIR_SCENARIO/.claude/state"
    echo "$$" > "$TMPDIR_SCENARIO/.claude/state/orchestrate.lock"
    ```
  - Args: any (fixture irrelevant — the mock is never invoked).
  - Assertions (spec §5 row 6):
    - `assert_eq "$SCN_EXIT" 1`.
    - `assert_contains "$SCN_OUT" "already running"` — the abort message.
    - **Empty/absent log:** the mock was never called → `$SCN_LOG` may not exist. Assert zero invocations tolerantly (e.g. `assert_log_count "$SCN_LOG" implement 0` plus a check that the file is empty or absent). Do not let a missing file error the suite.

- [ ] **Step 6.2: Run the full suite — all six scenarios green (victory condition).**

  **Definition of done — this is the authority gate for the whole feature:**
  ```bash
  scripts/test/test-orchestrate.sh; echo "suite exit=$?"
  ```
  Expected: 6 scenarios PASS, 0 FAIL; `suite exit=0`. Run it **twice** to confirm hermeticity (no leaked dated artifacts, no leaked lock in the live tree):
  ```bash
  git status --porcelain   # expect: only the staged test file changes, no stray .claude/state or docs/superpowers artifacts
  ```

- [ ] **Step 6.3: Commit.**

  ```bash
  git add scripts/test/test-orchestrate.sh
  git commit -m "test(orchestrate): scenario 6 (lock contention) + full 6-scenario suite green"
  ```

---

## Task 7: Documentation + dual-gate record `(S)`

**Files:**
- Modify: `docs/progress.md`
- Modify (optional, low-cost): `docs/adr/0007-multi-model-loop-harness.md`

- [ ] **Step 7.1: Re-run the Java baseline and record both gates.**

  Run the CLAUDE.md §3 gate once more for the close-out record:
  ```bash
  ./gradlew test --rerun-tasks
  ```
  Expected: 26 tests, 0 failures (unchanged — no Java touched).

- [ ] **Step 7.2: Update `docs/progress.md`.** Add a trajectory entry stating, explicitly (spec §5):
  - **Authority gate:** `scripts/test/test-orchestrate.sh` — 6/6 scenarios green; this is what certifies the feature.
  - **Java gate (silent here):** `./gradlew test` → 26 tests, 0 failures — run and recorded per the unconditional §3 contract, but it exercises **zero** lines of this feature and certifies nothing about it.
  - The `CLAUDE_BIN` seam is additive and a no-op in production (unset → real `claude`).

- [ ] **Step 7.3 (optional): Cross-link in ADR 0007.** Add a short note that `orchestrate.sh`'s control flow is now regression-guarded by the `CLAUDE_BIN` seam + `scripts/test/test-orchestrate.sh`, and that `CLAUDE_BIN` is the documented model-invocation extraction point (spec §3.7 / CLAUDE.md §7 spirit at the tooling layer).

- [ ] **Step 7.4: Note the deferred open questions (spec §7).** In the progress entry or ADR note, record as deferred (not done): (1) wiring the suite into `.github/workflows/ci.yml` — a human call; (2) a periodic real-`claude` mock-drift smoke; (3) per-command fixture keying for non-implement-phase failures. None are in scope here.

- [ ] **Step 7.5: Commit.**

  ```bash
  git add docs/progress.md docs/adr/0007-multi-model-loop-harness.md
  git commit -m "docs: record dual-gate result + CLAUDE_BIN seam regression guard for the loop"
  ```

---

## Self-Review Checklist

**Spec coverage:**
- [ ] §3.1 seam — single-line `"${CLAUDE_BIN:-claude}"` edit, only change to `orchestrate.sh` → Task 1
- [ ] §3.3 mock: leak guard (`MOCK_CLAUDE=1`, exit 99) → Task 2.2
- [ ] §3.3 mock: parse `/<command>` + `--model`; ignore `-p`/`--dangerously-skip-permissions`/`--verbose` → Task 2.1
- [ ] §3.2/§3.6 fixture consumed **only** by `implement`/`advisor`; non-implement → exit 0, no marker, no consume → Task 2.1/2.4, "fixture-consumption model" section
- [ ] §3.3 step 6 `/handoff` + unknown default branch (exit 0, no side effect) → Task 2.1
- [ ] §3.4 invocation log `<command>|<model>|<exit_code>` is the primary assertion surface → Tasks 2.3, 3.1
- [ ] §3.5 per-scenario `mktemp -d` isolation + `SLEEP_BETWEEN=0` → Task 3.1
- [ ] §5 row 1 happy path (exact full sequence, correct models) → Task 3.2
- [ ] §5 row 2 marker-driven completion (3 implement, no advisor) → Task 4.1
- [ ] §5 row 3 escalate-at-3 (3 implement fails → advisor success + banner) → Task 5.1
- [ ] §5 row 4 circuit breaker (3 implement + 2 advisor fails, exit 1, banner) → Task 5.2
- [ ] §5 row 5 `--skip-to` (no brainstorm/spec/plan; first line implement) → Task 4.2
- [ ] §5 row 6 lock contention (live `$$`, exit 1, "already running", empty/absent log) → Task 6.1
- [ ] §5 dual-gate: shell suite is authority; Java gate run+recorded but silent → "Read first" section, Tasks 0.1, 7.1–7.2
- [ ] §6 rollback is `git rm` the two test files + revert one line → inherent (additive design)

**Assertion-altitude consistency (only Scenario 1 is exact-match):**
- Scenario 1 → `assert_log_seq` (exact 7-line sequence)
- Scenarios 2, 3, 4 → `assert_log_count` / `assert_log_absent` / `assert_contains` (counts, presence, banners) because review/simplify/handoff trail the loop (2, 3) or the loop exits early (4)
- Scenario 5 → `assert_log_absent` + `assert_log_first`
- Scenario 6 → `assert_eq` exit + `assert_contains` banner + tolerant empty-log assert

**Footguns pinned:**
- [ ] Both new scripts `chmod +x` (Task 0.3) — orchestrate invokes `CLAUDE_BIN` as an absolute path
- [ ] `set +e` + `pipefail` (already set in orchestrate line 19) → mock exit code propagates through `| tee` into `phase_exit` — no change needed, verified
- [ ] Scenario 6 empty-log assertion tolerates a non-existent `MOCK_CLAUDE_LOG`
- [ ] Banner matches use plain substrings (ANSI color codes surround them)
