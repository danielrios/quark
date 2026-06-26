#!/usr/bin/env bash
# =============================================================================
# orchestrate.sh — Multi-model autonomous loop for quark
# =============================================================================
# Opus orchestrates brainstorm → spec → plan phases.
# Sonnet implements (fast, cheap, full-codebase context).
# Opus reviews (cross-model adversarial review).
# Sonnet simplifies.
# Escalation: Sonnet fails 3× → Opus advisor (2 more attempts) → circuit breaker.
#
# Usage:
#   ./scripts/orchestrate.sh --feature "Plan 3 — Telegram streaming"
#   ./scripts/orchestrate.sh --feature "Plan 3" --skip-to implement
#   ./scripts/orchestrate.sh --feature "Plan 3" --max-impl-iterations 10
#
# See: docs/adr/0007-multi-model-loop-harness.md
# =============================================================================

set -euo pipefail

# --- Configuration -----------------------------------------------------------
OPUS_MODEL="${OPUS_MODEL:-opus}"
SONNET_MODEL="${SONNET_MODEL:-sonnet}"
MAX_IMPL_ITERATIONS="${MAX_IMPL_ITERATIONS:-15}"
MAX_SONNET_FAILURES=3
MAX_ADVISOR_FAILURES=2
TOTAL_FAILURE_CEILING=5
SLEEP_BETWEEN="${SLEEP_BETWEEN:-5}"
LOG_DIR=".claude/state/loop-logs"
LOCK_FILE=".claude/state/orchestrate.lock"
SKIP_PERMISSIONS="--dangerously-skip-permissions"

# --- Colors ------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m'

# --- Parse args --------------------------------------------------------------
FEATURE=""
SKIP_TO=""
FEATURE_SLUG=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --feature)          FEATURE="$2"; shift 2 ;;
    --skip-to)          SKIP_TO="$2"; shift 2 ;;
    --max-impl-iterations) MAX_IMPL_ITERATIONS="$2"; shift 2 ;;
    --opus-model)       OPUS_MODEL="$2"; shift 2 ;;
    --sonnet-model)     SONNET_MODEL="$2"; shift 2 ;;
    --sleep)            SLEEP_BETWEEN="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: orchestrate.sh --feature \"<description>\" [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --feature NAME          Feature to implement (required)"
      echo "  --skip-to PHASE         Skip to: brainstorm|spec|plan|implement|review|simplify"
      echo "  --max-impl-iterations N Max implementation loop iterations (default: 15)"
      echo "  --opus-model MODEL      Opus model alias (default: opus)"
      echo "  --sonnet-model MODEL    Sonnet model alias (default: sonnet)"
      echo "  --sleep SECONDS         Cooldown between phases (default: 5)"
      exit 0
      ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ -z "$FEATURE" ]]; then
  echo -e "${RED}Error: --feature is required${NC}"
  exit 1
fi

# Slugify the feature name for file paths
FEATURE_SLUG="$(echo "$FEATURE" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g' | sed 's/--*/-/g' | sed 's/^-//;s/-$//')"
DATE="$(date '+%Y-%m-%d')"

# --- Lock (prevent duplicate runs) -------------------------------------------
mkdir -p "$(dirname "$LOCK_FILE")" "$LOG_DIR"
if [[ -f "$LOCK_FILE" ]]; then
  pid=$(cat "$LOCK_FILE" 2>/dev/null || echo "")
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    echo -e "${RED}Orchestration already running (PID $pid). Aborting.${NC}"
    exit 1
  fi
fi
echo $$ > "$LOCK_FILE"
trap 'rm -f "$LOCK_FILE"' EXIT

# --- Banner ------------------------------------------------------------------
echo -e "${PURPLE}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${PURPLE}║      quark — Multi-Model Orchestration Loop                 ║${NC}"
echo -e "${PURPLE}╠══════════════════════════════════════════════════════════════╣${NC}"
echo -e "${PURPLE}║  Feature:  ${CYAN}${FEATURE}${PURPLE}${NC}"
echo -e "${PURPLE}║  Opus:     ${GREEN}${OPUS_MODEL}${PURPLE}  (brainstorm, spec, plan, review)     ║${NC}"
echo -e "${PURPLE}║  Sonnet:   ${GREEN}${SONNET_MODEL}${PURPLE}  (implement, simplify)                ║${NC}"
echo -e "${PURPLE}║  Impl cap: ${GREEN}${MAX_IMPL_ITERATIONS}${PURPLE} iterations                              ║${NC}"
echo -e "${PURPLE}║  Escal.:   ${YELLOW}3 Sonnet fails → Opus advisor (2 more) → stop${PURPLE}  ║${NC}"
echo -e "${PURPLE}╚══════════════════════════════════════════════════════════════╝${NC}"

# --- Helper functions --------------------------------------------------------
run_phase() {
  local phase="$1"
  local model="$2"
  local prompt="$3"
  local timestamp
  timestamp=$(date '+%Y-%m-%d_%H-%M-%S')
  local log_file="${LOG_DIR}/${phase}-${timestamp}.log"

  echo ""
  echo -e "${BLUE}━━━ Phase: ${CYAN}${phase}${BLUE} ━━━ Model: ${GREEN}${model}${BLUE} ━━━ $(date '+%H:%M:%S') ━━━${NC}"

  set +e
  claude -p "$prompt" \
    --model "$model" \
    $SKIP_PERMISSIONS \
    --verbose \
    2>&1 | tee "$log_file"
  local exit_code=$?
  set -e

  if [[ $exit_code -eq 0 ]]; then
    echo -e "${GREEN}✓ Phase ${phase} completed successfully${NC}"
  else
    echo -e "${RED}✗ Phase ${phase} failed (exit ${exit_code})${NC}"
  fi

  return $exit_code
}

should_skip() {
  local phase="$1"
  if [[ -z "$SKIP_TO" ]]; then return 1; fi
  local phases=("brainstorm" "spec" "plan" "implement" "review" "simplify")
  local skip_idx=-1
  local phase_idx=-1
  for i in "${!phases[@]}"; do
    [[ "${phases[$i]}" == "$SKIP_TO" ]] && skip_idx=$i
    [[ "${phases[$i]}" == "$phase" ]] && phase_idx=$i
  done
  (( phase_idx < skip_idx ))
}

# --- Phase prompts -----------------------------------------------------------
# Each prompt instructs the agent to read CLAUDE.md (binding) and existing
# project context, then produce a specific artifact.

BRAINSTORM_PROMPT="$(cat <<'PROMPT'
You are running in autonomous mode inside the quark project.

## Your role: Brainstorm Agent

Read these files FIRST:
- CLAUDE.md (binding contract)
- ARCHITECTURE.md
- MANIFESTO.md
- docs/progress.md

## Task
Brainstorm approaches for the following feature:
FEATURE_PLACEHOLDER

Create the file: docs/superpowers/brainstorms/DATE_PLACEHOLDER-SLUG_PLACEHOLDER.md

Include:
1. Problem statement (1 paragraph)
2. Three distinct approaches with trade-offs
3. Recommended approach with justification
4. Risks and mitigations
5. Dependencies on existing code

## Rules
- Follow MANIFESTO.md philosophy (explicit > magical, streaming-first)
- Do NOT implement anything — brainstorm only
- Reference ADRs where relevant
- git add and commit with message: "docs: brainstorm for FEATURE_PLACEHOLDER"
PROMPT
)"

SPEC_PROMPT="$(cat <<'PROMPT'
You are running in autonomous mode inside the quark project.

## Your role: Spec Writer

Read these files FIRST:
- CLAUDE.md (binding contract)
- ARCHITECTURE.md
- The brainstorm: docs/superpowers/brainstorms/DATE_PLACEHOLDER-SLUG_PLACEHOLDER.md
- Existing specs in docs/superpowers/specs/ for format reference

## Task
Write a formal specification for:
FEATURE_PLACEHOLDER

Create the file: docs/superpowers/specs/DATE_PLACEHOLDER-SLUG_PLACEHOLDER.md

Follow the format of existing specs. Include:
1. Goal (1 sentence)
2. Non-goals (what this does NOT do)
3. Design (detailed technical approach)
4. API surface / interfaces
5. Test strategy (what tests prove it works)
6. Rollback plan

## Rules
- Follow CLAUDE.md §6 (YAGNI/KISS)
- Do NOT implement anything — spec only
- Reference the brainstorm decisions
- git add and commit with message: "docs: spec for FEATURE_PLACEHOLDER"
PROMPT
)"

PLAN_PROMPT="$(cat <<'PROMPT'
You are running in autonomous mode inside the quark project.

## Your role: Planner

Read these files FIRST:
- CLAUDE.md (binding contract — especially §3 test gate, §4 failure escalation)
- The spec: docs/superpowers/specs/DATE_PLACEHOLDER-SLUG_PLACEHOLDER.md
- Existing plans in docs/superpowers/plans/ for format reference
- docs/progress.md for current project state

## Task
Create an ordered implementation plan for:
FEATURE_PLACEHOLDER

Create the file: docs/superpowers/plans/DATE_PLACEHOLDER-SLUG_PLACEHOLDER.md

Follow the format of existing plans. Each task must:
1. Have a clear, testable definition of done
2. Reference the test gate (CLAUDE.md §3)
3. Follow WIP=1 (one task at a time)
4. Include estimated complexity (S/M/L)

## Rules
- Tasks must be ordered by dependency
- Each task must be completable in a single Claude Code session
- Include a "Task 0: setup/scaffolding" if needed
- git add and commit with message: "docs: plan for FEATURE_PLACEHOLDER"
PROMPT
)"

# Implementation prompt is built dynamically per task (see loop below)

REVIEW_PROMPT="$(cat <<'PROMPT'
You are running in autonomous mode inside the quark project.

## Your role: Adversarial Reviewer

You are reviewing code written by a DIFFERENT model (Sonnet). Your job is to
find bugs, spec violations, and simplification opportunities that Sonnet missed.

Read these files FIRST:
- CLAUDE.md (binding contract — especially §6 YAGNI, §7 no drift, §8 lifecycle)
- The spec: docs/superpowers/specs/DATE_PLACEHOLDER-SLUG_PLACEHOLDER.md
- The plan: docs/superpowers/plans/DATE_PLACEHOLDER-SLUG_PLACEHOLDER.md
- docs/progress.md

Then examine the implementation:
- Run: git diff main..HEAD --stat (to see what changed)
- Run: git diff main..HEAD (to see the actual changes)
- Read the changed files in full

## Task
Write a review: docs/superpowers/reviews/DATE_PLACEHOLDER-SLUG_PLACEHOLDER-review.md

Organize findings by severity:
1. **Blockers** — Bugs, spec violations, test gaps that must be fixed
2. **Improvements** — YAGNI violations, unnecessary complexity, missing docs
3. **Nits** — Style, naming, minor cleanup

For each finding:
- Quote the relevant code
- Explain the problem
- Suggest a fix

## Verification
- Run the test gate: first try mcp__quarkus-agent__quarkus_callTool with
  toolName="devui-testing_runTests". If MCP is unreachable, fall back to
  ./gradlew test as the gate.
- Run ./gradlew spotlessCheck
- Report results in the review

## Rules
- Do NOT fix anything — review only
- Be rigorous but fair — flag real issues, not style preferences
- git add and commit with message: "docs: review for FEATURE_PLACEHOLDER"
PROMPT
)"

SIMPLIFY_PROMPT="$(cat <<'PROMPT'
You are running in autonomous mode inside the quark project.

## Your role: Simplifier

Read these files FIRST:
- CLAUDE.md (binding contract — especially §6 YAGNI/KISS, §8 lifecycle discipline)
- The review: docs/superpowers/reviews/DATE_PLACEHOLDER-SLUG_PLACEHOLDER-review.md
- docs/progress.md

## Task
Address each finding from the review:
1. Fix all **Blockers**
2. Address **Improvements** that align with CLAUDE.md §6
3. Apply **Nits** if they improve readability

## Verification
After EACH change:
- Run the test gate: first try mcp__quarkus-agent__quarkus_callTool with
  toolName="devui-testing_runTests". If MCP is unreachable, fall back to
  ./gradlew test.
- Run ./gradlew spotlessCheck
- Do NOT proceed to the next fix if tests fail

## Rules
- Follow CLAUDE.md §3 (test gate before and after)
- Follow CLAUDE.md §4 (3-strike escalation)
- Update docs/progress.md via the /progress command
- Commit each logical fix separately with descriptive messages
PROMPT
)"

# --- Substitute placeholders -------------------------------------------------
substitute() {
  local text="$1"
  echo "$text" \
    | sed "s|FEATURE_PLACEHOLDER|${FEATURE}|g" \
    | sed "s|DATE_PLACEHOLDER|${DATE}|g" \
    | sed "s|SLUG_PLACEHOLDER|${FEATURE_SLUG}|g"
}

# --- Phase execution ---------------------------------------------------------

# BRAINSTORM
if ! should_skip "brainstorm"; then
  mkdir -p docs/superpowers/brainstorms
  run_phase "brainstorm" "$OPUS_MODEL" "$(substitute "$BRAINSTORM_PROMPT")" || {
    echo -e "${RED}Brainstorm phase failed. Aborting.${NC}"
    exit 1
  }
  sleep "$SLEEP_BETWEEN"
fi

# SPEC
if ! should_skip "spec"; then
  run_phase "spec" "$OPUS_MODEL" "$(substitute "$SPEC_PROMPT")" || {
    echo -e "${RED}Spec phase failed. Aborting.${NC}"
    exit 1
  }
  sleep "$SLEEP_BETWEEN"
fi

# PLAN
if ! should_skip "plan"; then
  run_phase "plan" "$OPUS_MODEL" "$(substitute "$PLAN_PROMPT")" || {
    echo -e "${RED}Plan phase failed. Aborting.${NC}"
    exit 1
  }
  sleep "$SLEEP_BETWEEN"
fi

# IMPLEMENT — Inner loop with escalation
if ! should_skip "implement"; then
  echo ""
  echo -e "${PURPLE}━━━ Entering implementation loop ━━━${NC}"

  iteration=0
  consecutive_failures=0
  advisor_attempts=0
  in_advisor_mode=false

  while (( iteration < MAX_IMPL_ITERATIONS )); do
    ((iteration++))

    # Build the implementation prompt dynamically
    IMPL_PROMPT="$(cat <<IMPLEOF
You are running in autonomous mode inside the quark project.

## Your role: Implementer (iteration ${iteration}/${MAX_IMPL_ITERATIONS})

Read these files FIRST:
- CLAUDE.md (binding contract — ALL rules are active)
- The plan: docs/superpowers/plans/${DATE}-${FEATURE_SLUG}.md
- docs/progress.md (current state)

## Task
Execute the NEXT unchecked task from the plan.
Look for tasks marked with \`- [ ]\` and execute the first one.

## Tool strategy
- **Primary:** Use mcp__quarkus-agent__quarkus_callTool with
  toolName="devui-testing_runTests" for the test gate.
  Use mcp__quarkus-agent__quarkus_logs for log inspection.
  Use mcp__quarkus-agent__quarkus_searchDocs for official Quarkus docs.
- **Supplementary docs:** Use mcp__context7__resolve-library-id and
  mcp__context7__query-docs for Quarkiverse, langchain4j, and other
  libraries not covered by quarkus-agent docs.
- **Fallback:** If MCP is unreachable, use ./gradlew test as the gate.

## After implementation
1. Run the test gate (see tool strategy above)
2. Run ./gradlew spotlessCheck
3. Mark the task as done in the plan: change \`- [ ]\` to \`- [x]\`
4. Update docs/progress.md
5. git add and commit with a descriptive message

## Completion signal
If ALL tasks in the plan are checked \`[x]\`, create the file:
.claude/state/IMPL_COMPLETE

## Rules
- CLAUDE.md §2: WIP=1 — one task per iteration
- CLAUDE.md §3: test gate before and after
- CLAUDE.md §4: if test fails 3 times with same error, STOP and write to progress.md
IMPLEOF
)"

    if $in_advisor_mode; then
      # Opus advisor mode — deeper diagnosis
      IMPL_PROMPT="$(cat <<ADVISOREOF
You are running in autonomous mode inside the quark project.

## Your role: Opus Advisor (attempt ${advisor_attempts}/${MAX_ADVISOR_FAILURES})

The Sonnet implementer has failed ${MAX_SONNET_FAILURES} consecutive times on
the current task. You are called in as an expert to diagnose and fix the issue.

Read these files FIRST:
- CLAUDE.md (binding contract — especially §4, §8)
- The plan: docs/superpowers/plans/${DATE}-${FEATURE_SLUG}.md
- docs/progress.md (includes the error traces from failed attempts)
- Recent loop logs in .claude/state/loop-logs/

## Task
1. Read the error traces in docs/progress.md
2. Diagnose the root cause
3. Fix the issue
4. Run the test gate:
   - Primary: mcp__quarkus-agent__quarkus_callTool with toolName="devui-testing_runTests"
   - For docs: mcp__quarkus-agent__quarkus_searchDocs (official Quarkus),
     mcp__context7__* (Quarkiverse, langchain4j, other libs)
   - Fallback: ./gradlew test
5. If successful, mark the task done in the plan and update progress.md
6. If the fix works, create .claude/state/IMPL_COMPLETE if all tasks are done
7. git add and commit

## Rules
- Follow CLAUDE.md §8 lifecycle discipline if touching CDI components
- Consult docs via MCP before making assumptions
ADVISOREOF
)"
      run_phase "advisor-${iteration}" "$OPUS_MODEL" "$IMPL_PROMPT"
      phase_exit=$?
    else
      run_phase "implement-${iteration}" "$SONNET_MODEL" "$IMPL_PROMPT"
      phase_exit=$?
    fi

    # Check for completion marker
    if [[ -f ".claude/state/IMPL_COMPLETE" ]]; then
      echo -e "${GREEN}✅ All implementation tasks complete!${NC}"
      rm -f ".claude/state/IMPL_COMPLETE"
      break
    fi

    # Handle failure / escalation
    if [[ $phase_exit -ne 0 ]]; then
      ((consecutive_failures++))

      if $in_advisor_mode; then
        ((advisor_attempts++))
        echo -e "${RED}Advisor attempt ${advisor_attempts}/${MAX_ADVISOR_FAILURES} failed${NC}"

        if (( advisor_attempts >= MAX_ADVISOR_FAILURES )); then
          echo -e "${RED}╔══════════════════════════════════════════════════════════╗${NC}"
          echo -e "${RED}║  CIRCUIT BREAKER: ${TOTAL_FAILURE_CEILING} total failures (Sonnet + Advisor)   ║${NC}"
          echo -e "${RED}║  Check: docs/progress.md and ${LOG_DIR}/          ║${NC}"
          echo -e "${RED}╚══════════════════════════════════════════════════════════╝${NC}"
          exit 1
        fi
      else
        echo -e "${YELLOW}Sonnet failure ${consecutive_failures}/${MAX_SONNET_FAILURES}${NC}"

        if (( consecutive_failures >= MAX_SONNET_FAILURES )); then
          echo -e "${YELLOW}╔══════════════════════════════════════════════════════════╗${NC}"
          echo -e "${YELLOW}║  Escalating to Opus advisor...                          ║${NC}"
          echo -e "${YELLOW}╚══════════════════════════════════════════════════════════╝${NC}"
          in_advisor_mode=true
          advisor_attempts=0
        fi
      fi
    else
      # Reset failure counters on success
      consecutive_failures=0
      advisor_attempts=0
      in_advisor_mode=false
    fi

    sleep "$SLEEP_BETWEEN"
  done
fi

# REVIEW
if ! should_skip "review"; then
  mkdir -p docs/superpowers/reviews
  run_phase "review" "$OPUS_MODEL" "$(substitute "$REVIEW_PROMPT")" || {
    echo -e "${YELLOW}Review phase had issues but continuing to simplify...${NC}"
  }
  sleep "$SLEEP_BETWEEN"
fi

# SIMPLIFY
if ! should_skip "simplify"; then
  run_phase "simplify" "$SONNET_MODEL" "$(substitute "$SIMPLIFY_PROMPT")" || {
    echo -e "${YELLOW}Simplify phase had issues. Manual review recommended.${NC}"
  }
  sleep "$SLEEP_BETWEEN"
fi

# --- Final gate ---------------------------------------------------------------
echo ""
echo -e "${BLUE}━━━ Final verification ━━━${NC}"
run_phase "final-gate" "$SONNET_MODEL" "$(cat <<FINALEOF
You are running in autonomous mode inside the quark project.

## Your role: Final Gate

Read CLAUDE.md. Run the full verification:

1. Test gate: first try mcp__quarkus-agent__quarkus_callTool with
   toolName="devui-testing_runTests". If unreachable, use ./gradlew test.
2. ./gradlew spotlessCheck
3. Verify docs/progress.md is up to date
4. Verify the plan has all tasks checked

If everything passes:
- Update docs/progress.md with final status
- Prepare a PR description summarizing the feature
- git add and commit with message: "feat: ${FEATURE}"

If anything fails, document it in docs/progress.md.
FINALEOF
)" || true

# --- Summary ------------------------------------------------------------------
echo ""
echo -e "${PURPLE}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${PURPLE}║  Orchestration complete                                     ║${NC}"
echo -e "${PURPLE}║  Feature: ${CYAN}${FEATURE}${NC}"
echo -e "${PURPLE}║  Logs:    ${LOG_DIR}/                                       ║${NC}"
echo -e "${PURPLE}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${YELLOW}Commits during this session:${NC}"
git log --oneline main..HEAD 2>/dev/null || echo "(no commits)"
echo ""
echo -e "${GREEN}Next: review the changes, then push and open a PR.${NC}"
