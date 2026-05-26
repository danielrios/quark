#!/usr/bin/env bash
# PostToolUse hook (Edit|Write|NotebookEdit) — advisory test-gate reminder.
# Stamps .claude/state/last-edit so the Stop hook can tell if edits happened since last commit.

set -u

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
state_dir="${PROJECT_DIR}/.claude/state"
mkdir -p "$state_dir"
date -u +%s > "${state_dir}/last-edit"

# Print to stderr so it shows up as a system message without polluting tool output.
echo "edit recorded — CLAUDE.md §3: run 'mcp__quarkus-agent__quarkus_callTool' with toolName='devui-testing_runTests' before declaring done." >&2

exit 0
