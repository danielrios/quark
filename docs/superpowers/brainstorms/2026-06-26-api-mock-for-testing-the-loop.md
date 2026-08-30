# Brainstorm — API mock for testing the loop

**Date:** 2026-06-26
**Slug:** api-mock-for-testing-the-loop
**Context:** [ADR 0007](../../adr/0007-multi-model-loop-harness.md), [`scripts/orchestrate.sh`](../../../scripts/orchestrate.sh), [MANIFESTO.md](../../../MANIFESTO.md)

---

## 1. Problem statement

`scripts/orchestrate.sh` is the multi-model autonomous loop: it sequences
brainstorm → spec → plan → implement → review → simplify, each by shelling out to
`claude -p "/<command>" --model <model>` in `run_phase()` (line 116). The loop's
*value* is not in any single phase but in its **control flow** — phase skipping
(`should_skip`, lines 133–144), the `IMPL_COMPLETE` completion marker
(line 198), consecutive-failure counting → Opus advisor escalation after 3 Sonnet
failures (lines 205–235), the circuit breaker after 2 more advisor failures
(5 total), and the lock file (lines 82–90). Today none of that logic is testable
without spending real Opus/Sonnet tokens and accepting the agent's
non-determinism — a single dry run costs money and can't be made to reproduce an
escalation or circuit-breaker path on demand. We need a way to drive the loop
through every branch **deterministically and for free**.

### A note on the wording: "API mock" vs. the actual goal

The phrase "API mock" literally points at the HTTP/Anthropic layer (Approach C
below). But the *stated goal* — "testing **the loop**" — is exit-code- and
marker-driven, not API-driven: the loop never inspects an API response body; it
reads only the **exit code** and **filesystem side effects** of each `claude`
call. So the level that actually serves the goal is mocking at the **CLI
boundary** (the `claude` invocation), not the wire. This tension is resolved
explicitly in the recommendation: we mock the command, not the API. Approach C is
retained because it answers a *different, legitimate* question (does the real CLI
+ MCP wiring work end-to-end?), not as a strawman.

### The mock's contract (shared by A and B)

Whatever mocks the `claude` call must reproduce the two things the loop reads
from a real invocation:

1. **Exit code** — drives `consecutive_failures`, advisor escalation, and the
   circuit breaker.
2. **Filesystem side effects** — the real `claude` *creates files* that later
   phases depend on by path:
   - the `IMPL_COMPLETE` marker (line 198) that breaks the implement loop;
   - the spec file the plan phase points at (`docs/superpowers/specs/${DATE}-${SLUG}.md`, line 169);
   - the review file the simplify phase reads (line 252);
   - the brainstorm/plan artifacts assumed present downstream.

   A pure "return code N" stub is therefore insufficient — the mock must know
   **which phase it is being called for** (by parsing the `/<command>` in the
   prompt, or by indexing into a scenario fixture by call count) and emit the
   matching marker/artifact.

---

## 2. Approaches

### Approach A — PATH-shadowing fake `claude` binary

A script named `claude`, placed on a test-only `PATH` entry ahead of the real
binary. `orchestrate.sh` is run unchanged; the shim reads a scenario fixture
(env var or file) describing per-call exit codes and which side-effect files to
`touch`, parsing the `/<command>` from its arguments to know the phase.

**Pros**
- Zero changes to `orchestrate.sh`; tests the production script byte-for-byte.
- Cheap, deterministic, no network, no tokens.
- Conceptually simple: one shell stub + a fixture format.

**Cons**
- `PATH` manipulation is implicit/"magical" — easy to get wrong, and a stray
  export can leak the fake `claude` into a *real* run (a genuine footgun on a
  loop that wields `--dangerously-skip-permissions`).
- The seam is invisible from reading `orchestrate.sh`; a reader can't tell the
  script is mockable.

**Complexity:** S

### Approach B — Explicit `CLAUDE_BIN` indirection seam *(recommended)*

Change the one call site to `"${CLAUDE_BIN:-claude}"` (line 116). Tests set
`CLAUDE_BIN=/path/to/mock-claude.sh`; production is untouched (default resolves
to the real `claude`). The mock is the same scripted, phase-aware stub as A —
honouring the contract in §1 — but invoked through a declared, greppable seam
instead of `PATH` order. A small shell test (see §3 verification) sets up a
fixture per scenario and asserts on exit code + which marker files appeared.

**Pros**
- **Explicit over magical** (MANIFESTO): the seam is one obvious, greppable
  variable; the script self-documents that it is mockable.
- **Additive evolution** (MANIFESTO): a single-line, backward-compatible change;
  no behaviour change when `CLAUDE_BIN` is unset.
- **Least power, agent layer** (CLAUDE.md §6): this is dev/test tooling, so a
  bash stub beats a server — the principle applies cleanly here in a way it
  would not for shipped product code.
- No leak risk: the mock only activates when a test deliberately sets the var.
- Tests every control-flow branch deterministically: success path, 3-fail
  escalation, 5-fail circuit breaker, `--skip-to`, lock contention, marker-driven
  completion.

**Cons**
- Touches the production script (minimal, but non-zero).
- Mock can drift from real `claude` side-effect behaviour; mitigated by keeping
  the contract narrow (§4).

**Complexity:** S

### Approach C — HTTP-level Anthropic API mock (`ANTHROPIC_BASE_URL`)

Stand up a local mock of the Anthropic Messages API (WireMock, a tiny
HTTP server, or similar) and point the **real** `claude` CLI at it via
`ANTHROPIC_BASE_URL` / `ANTHROPIC_API_KEY`. The genuine CLI agent loop runs
against canned model responses.

**Pros**
- Highest fidelity for a *different* question: exercises the actual `claude`
  binary, MCP wiring (`quarkus-agent`, `context7`), and slash-command rendering
  end-to-end — the only approach that does.
- Useful as an integration **smoke test** of the CLI/MCP plumbing.

**Cons**
- **Does not reliably test the loop's logic.** The CLI agent loop is
  non-deterministic — it *decides* which tools to call — so you cannot
  dependably make it `touch IMPL_COMPLETE` or write the spec file a downstream
  phase needs. The thing the loop branches on is exactly the thing C can't
  control. This is the killer argument against C *for loop-logic testing*.
- Heavy: a server, request matchers, auth, and Anthropic response-schema
  fidelity to maintain — far more than a bash stub.
- Tests the wrong layer: couples loop-flow tests to API wire format the loop
  never inspects.

**Complexity:** L

---

## 3. Recommendation

**Approach B — explicit `CLAUDE_BIN` seam + scripted, phase-aware mock.**

It is the only approach that tests what the loop is *for* (its control flow)
deterministically and for free, and it does so the way this repo is supposed to
build: **explicit > magical** and **additive** (MANIFESTO), **least power** at
the agent/tooling layer (CLAUDE.md §6). A is the same idea wearing a riskier,
implicit `PATH` hat; B costs one extra line to remove the leak footgun and make
the seam self-documenting. C is kept on the menu honestly — it is the right tool
for a *future* CLI/MCP integration smoke test, but it cannot drive the
escalation, circuit-breaker, or completion-marker branches, which are the whole
point here.

**Verification gate for this feature (read §4 risk 1 first).** This work lives in
`scripts/` + a new test, **not** `src/main/java`, so CLAUDE.md §3's Quarkus gate
(`devui-testing_runTests` / `./gradlew test`) does **not** certify it and must
not be inherited by default. The real gate is a **shell test**: a `bats` suite
(or plain `bash` with `set -e` and assertions) that, per scenario, sets
`CLAUDE_BIN` to the mock + a fixture, runs `orchestrate.sh`, and asserts on the
final exit code and which marker/artifact files were created. Done = every named
branch (happy path, escalate-at-3, break-at-5, `--skip-to`, lock contention)
has a green scenario.

---

## 4. Risks and mitigations

| # | Risk | Mitigation |
|---|------|------------|
| 1 | **Wrong gate inherited.** A reviewer assumes the Quarkus test gate covers this and ships it uncertified. | State the gate explicitly (§3): a shell/`bats` suite is the authority for `scripts/` changes; the Java gate is silent here. Record the chosen gate in the spec and `docs/progress.md`. |
| 2 | **Mock drift.** The stub's side-effect behaviour diverges from what real `claude` actually writes, so green tests certify a fiction. | Keep the contract narrow and documented (§1): exit code + the specific marker/artifact files each phase path depends on. Add one periodic real-`claude` smoke (overlaps with C's legitimate use) to confirm the contract still holds. |
| 3 | **Fake `claude` leaks into a real run** (acute under `--dangerously-skip-permissions`). | Prefer B's opt-in `CLAUDE_BIN` over A's `PATH` shadowing; the mock activates only when a test sets the var. Have the mock script refuse to run unless an explicit `MOCK_CLAUDE=1`-style guard is set. |
| 4 | **Scenario fixtures rot** as `orchestrate.sh` phases/paths change. | Co-locate fixtures with the test; key the mock off the `/<command>` token (stable) rather than hard-coded call indices where possible; one assertion per branch keeps failures legible. |
| 5 | **Scope creep toward C.** Temptation to build the HTTP mock "while we're here." | YAGNI/KISS (CLAUDE.md §6): ship B only. C is a separately-justified future smoke test, not part of this feature. |

---

## 5. Dependencies on existing code

- **[`scripts/orchestrate.sh`](../../../scripts/orchestrate.sh)** — the system under test. Specific load-bearing points the mock/test must honour:
  - `run_phase()` line 116 — the single `claude -p … --model …` call site; the **one** edit point for the `CLAUDE_BIN` seam.
  - line 198 — `IMPL_COMPLETE` marker check that breaks the implement loop (`.claude/state/IMPL_COMPLETE`).
  - lines 205–235 — `consecutive_failures` / `advisor_attempts` / `in_advisor_mode` escalation and the 5-total circuit breaker.
  - `should_skip()` lines 133–144 — phase ordering for `--skip-to` scenarios.
  - lines 82–90 — `.claude/state/orchestrate.lock` (lock-contention scenario) and the `trap … EXIT` cleanup.
  - line 169 / line 252 — downstream phases that read `docs/superpowers/specs/${DATE}-${SLUG}.md` and `docs/superpowers/reviews/${DATE}-${SLUG}-review.md` by path; the mock must create these for the spec/plan/review/simplify chain to proceed.
- **`.claude/commands/*.md`** — only the slash-command *names* matter to the mock (it parses `/<command>` from the prompt); their content is irrelevant to loop-flow tests.
- **[ADR 0007](../../adr/0007-multi-model-loop-harness.md)** — defines the phase→model routing and escalation protocol this mock must be able to reproduce; the spec should cite it for the canonical branch list.
- **[`docker-compose.claude.yml`](../../../docker-compose.claude.yml)** — passes `ANTHROPIC_API_KEY` and would be the host for Approach C's `ANTHROPIC_BASE_URL` override; **not** needed for the recommended B.
- **No dependency on `src/main/java`** — this feature does not touch the Quarkus application; see risk 1 on the verification-gate implication.

> Brainstorm only — no implementation. Next step: `/spec` to formalize the
> `CLAUDE_BIN` seam, the mock contract, the scenario matrix, and the shell-test gate.
