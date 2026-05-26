#!/usr/bin/env bash
# PreToolUse hook for Bash — blocks foreground dev-mode invocations per CLAUDE.md §2.
# Contract: stdin is a JSON object with {tool_input: {command: "..."}}. Exit 2 = deny + show stderr.

set -u

payload="$(cat)"

# Parse command from JSON. Prefer jq for correctness; fall back to greedy sed.
if command -v jq >/dev/null 2>&1; then
  cmd="$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null || true)"
else
  cmd="$(printf '%s' "$payload" | tr '\n' ' ' | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\(.*\)".*/\1/p')"
fi

[[ -z "$cmd" ]] && exit 0

# Patterns the allow/deny syntax in settings.json can't express on its own
# (pipes, command chains, regex on args). Keep settings.json as the visible
# source of truth for plain prefix matches; this hook covers patterns only.
deny_patterns=(
  'quarkusDev'
  '--continuous'
  'gradlew[[:space:]]+-t([[:space:]]|$)'
  'quarkus:dev'
)

for pat in "${deny_patterns[@]}"; do
  if printf '%s' "$cmd" | grep -Eq -e "$pat"; then
    echo "Blocked by .claude/hooks/pre-bash-guard.sh: '$cmd'" >&2
    echo "CLAUDE.md §2 forbids foreground/blocking dev-mode invocations." >&2
    echo "Use the quarkus-agent MCP instead:" >&2
    echo "  mcp__quarkus-agent__quarkus_start" >&2
    echo "  mcp__quarkus-agent__quarkus_status" >&2
    echo "  mcp__quarkus-agent__quarkus_logs" >&2
    exit 2
  fi
done

exit 0
