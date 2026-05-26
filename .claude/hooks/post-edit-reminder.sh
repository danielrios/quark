#!/usr/bin/env bash
# PostToolUse hook (Edit|Write|NotebookEdit) — advisory test-gate reminder.
# Only fires on source files (.java, .kt, .kts, .gradle.kts, .properties).
# Stamps .claude/state/last-edit for the Stop hook.

set -u

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
state_dir="${PROJECT_DIR}/.claude/state"
payload="$(cat)"

# Extract the edited file path. Prefer jq.
if command -v jq >/dev/null 2>&1; then
  file_path="$(printf '%s' "$payload" | jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' 2>/dev/null || true)"
else
  file_path="$(printf '%s' "$payload" | tr '\n' ' ' | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
fi

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
