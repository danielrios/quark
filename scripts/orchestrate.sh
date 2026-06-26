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

# --- Phase execution ---------------------------------------------------------

# BRAINSTORM
if ! should_skip "brainstorm"; then
  mkdir -p docs/superpowers/brainstorms
  run_phase "brainstorm" "$OPUS_MODEL" "/brainstorm ${FEATURE}" || {
    echo -e "${RED}Brainstorm phase failed. Aborting.${NC}"
    exit 1
  }
  sleep "$SLEEP_BETWEEN"
fi

# SPEC
if ! should_skip "spec"; then
  run_phase "spec" "$OPUS_MODEL" "/spec ${FEATURE}" || {
    echo -e "${RED}Spec phase failed. Aborting.${NC}"
    exit 1
  }
  sleep "$SLEEP_BETWEEN"
fi

# PLAN
if ! should_skip "plan"; then
  run_phase "plan" "$OPUS_MODEL" "/plan docs/superpowers/specs/${DATE}-${FEATURE_SLUG}.md" || {
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

    if $in_advisor_mode; then
      run_phase "advisor-${iteration}" "$OPUS_MODEL" "/advisor docs/superpowers/plans/${DATE}-${FEATURE_SLUG}.md"
      phase_exit=$?
    else
      run_phase "implement-${iteration}" "$SONNET_MODEL" "/implement docs/superpowers/plans/${DATE}-${FEATURE_SLUG}.md"
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
  run_phase "review" "$OPUS_MODEL" "/review docs/superpowers/specs/${DATE}-${FEATURE_SLUG}.md" || {
    echo -e "${YELLOW}Review phase had issues but continuing to simplify...${NC}"
  }
  sleep "$SLEEP_BETWEEN"
fi

# SIMPLIFY
if ! should_skip "simplify"; then
  run_phase "simplify" "$SONNET_MODEL" "/simplify docs/superpowers/reviews/${DATE}-${FEATURE_SLUG}-review.md" || {
    echo -e "${YELLOW}Simplify phase had issues. Manual review recommended.${NC}"
  }
  sleep "$SLEEP_BETWEEN"
fi

# --- Final gate ---------------------------------------------------------------
echo ""
echo -e "${BLUE}━━━ Final verification ━━━${NC}"
run_phase "final-gate" "$SONNET_MODEL" "/handoff ${FEATURE}" || true
run_phase "finish" "$SONNET_MODEL" "/finish" || true

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
