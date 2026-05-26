#!/usr/bin/env bash
# SessionStart hook for quark — advisory only, always exits 0.
# Surfaces setup gaps loudly so the agent doesn't hit silent tool failures later.

set -u

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
warnings=()

# --- Java 25 check -----------------------------------------------------------
if command -v java >/dev/null 2>&1; then
  # `java -version` prints to stderr; grab the first line.
  java_version_line="$(java -version 2>&1 | head -n1)"
  # Matches: openjdk version "25" / "25.0.1" / java version "25"
  if ! echo "$java_version_line" | grep -Eq '"25(\.|")'; then
    warnings+=("Java 25 expected per CLAUDE.md; detected: ${java_version_line}")
  fi
else
  warnings+=("'java' not on PATH; CLAUDE.md requires Java 25.")
fi

# --- Gradle wrapper check ----------------------------------------------------
if [[ ! -x "${PROJECT_DIR}/gradlew" ]]; then
  warnings+=("./gradlew not found or not executable at ${PROJECT_DIR}/gradlew")
fi

# --- quarkus-agent MCP detection --------------------------------------------
# Look in the standard places. We only check that *something* mentions it —
# this is a heuristic, not a connection test.
mcp_found=0
for candidate in \
  "${PROJECT_DIR}/.mcp.json" \
  "${PROJECT_DIR}/.claude/settings.local.json" \
  "${HOME}/.claude.json" \
  "${HOME}/.claude/settings.json"; do
  if [[ -f "$candidate" ]] && grep -q 'quarkus-agent' "$candidate" 2>/dev/null; then
    mcp_found=1
    break
  fi
done
if [[ $mcp_found -eq 0 ]]; then
  warnings+=("quarkus-agent MCP not detected in .mcp.json or merged Claude settings. CLAUDE.md §2/§3 test+dev workflow depends on it — see docs/adr/0004-claude-code-harness.md.")
fi

# --- Banner ------------------------------------------------------------------
echo "── quark harness ──────────────────────────────────────────────"
if [[ -f "${PROJECT_DIR}/docs/progress.md" ]]; then
  # First bullet under '## Current Task' (or legacy '## Current Goal').
  current="$(awk '/^## (Current Task|Current Goal)/{flag=1;next} /^## /{flag=0} flag && /^- /{print;exit}' "${PROJECT_DIR}/docs/progress.md" 2>/dev/null || true)"
  if [[ -n "$current" ]]; then
    echo "next task: ${current#- }"
  fi
fi
if (( ${#warnings[@]} > 0 )); then
  echo
  echo "⚠ setup warnings:"
  for w in "${warnings[@]}"; do
    echo "  - $w"
  done
fi
echo "harness affordances: /baseline-test, /progress | denied: ./gradlew quarkusDev (use mcp__quarkus-agent__quarkus_start)"
echo "───────────────────────────────────────────────────────────────"

exit 0
