#!/usr/bin/env bash
# PreToolUse hook for Bash — blocks foreground dev-mode invocations per CLAUDE.md §2.
# Contract: stdin is a JSON object with {tool_input: {command: "..."}}. Exit 2 = deny + show stderr.

set -u

payload="$(cat)"

# Pull command out of the JSON without requiring jq.
# Tolerate single-line or pretty-printed input.
cmd="$(printf '%s' "$payload" | tr '\n' ' ' | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\(.*\)".*/\1/p')"

if [[ -z "$cmd" ]]; then
  exit 0
fi

# Patterns that block the session (CLAUDE.md "Non-Interactive" rule).
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
