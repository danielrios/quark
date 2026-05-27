#!/usr/bin/env bash
# PostToolUse hook (Edit|Write|NotebookEdit) — advisory test-gate reminder.
# Only fires on source files (.java, .kt, .kts, .gradle.kts, .properties).
# Stamps .claude/state/last-edit for the Stop hook.

set -u

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
state_dir="${PROJECT_DIR}/.claude/state"
payload="$(cat)"

# jq is required for reliable parsing — a sed fallback breaks on escaped
# quotes and Unicode in paths. Mirror pre-bash-guard.sh: fail open with a
# warning rather than risk a wrong stamp.
if ! command -v jq >/dev/null 2>&1; then
  echo "post-edit-reminder.sh: jq not on PATH — skipping test-gate reminder (install jq to re-enable)." >&2
  exit 0
fi

file_path="$(printf '%s' "$payload" | jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' 2>/dev/null || true)"

case "$file_path" in
  *.java|*.kt|*.kts|*.gradle.kts|*.properties)
    mkdir -p "$state_dir"
    date -u +%s > "${state_dir}/last-edit"
    echo "source edit recorded — CLAUDE.md §3: run 'mcp__quarkus-agent__quarkus_callTool' with toolName='devui-testing_runTests' before declaring done." >&2
    ;;
  *)
    : # doc/config/markdown edits — silent
    ;;
esac

exit 0
