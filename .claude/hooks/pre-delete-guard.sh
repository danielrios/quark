#!/usr/bin/env bash
# PreToolUse hook for Bash — advisory CLAUDE.md §8 reminder when a deletion command
# (rm / git rm) targets a lifecycle-bearing src/main source file. Non-blocking: it
# warns and exits 0 (the user chose warn-not-block). Stays silent for plain classes,
# non-deletion commands, and non-src/main paths, so it only fires on the cases §8
# actually cares about. Contract: stdin is JSON {tool_input:{command:"..."}}.

set -u

payload="$(cat)"

# jq required for reliable parsing; pre-bash-guard.sh already warns when it's missing,
# so here we just fail open silently rather than double-warn.
command -v jq >/dev/null 2>&1 || exit 0

cmd="$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null || true)"
[[ -z "$cmd" ]] && exit 0

# Only consider deletion commands: `rm ...` or `git rm ...` (word-bounded so npm/confirm
# etc. don't match).
printf '%s' "$cmd" | grep -Eq '(^|[[:space:]])(rm|git[[:space:]]+rm)([[:space:]]|$)' || exit 0

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# Markers that make a class lifecycle-bearing per §8.
markers='@ApplicationScoped|@RequestScoped|@Singleton|@SessionScoped|@Produces|@PreDestroy|@Observes|ChatMemoryStore|ChatMemory|implements[[:space:]].*Store'

# The file still exists at PreToolUse time (the rm has not run yet), so inspect content.
for tok in $cmd; do
  case "$tok" in
    *src/main/*.java)
      f="$tok"
      [[ -f "$f" ]] || f="${PROJECT_DIR}/${tok}"
      [[ -f "$f" ]] || continue
      if grep -Eq -e "$markers" "$f"; then
        echo "§8 reminder (.claude/hooks/pre-delete-guard.sh): deleting lifecycle-bearing '$tok'." >&2
        echo "Before deleting a CDI-scope / bean / memory / @PreDestroy component:" >&2
        echo "  1. verify its documented lifecycle behaviour via the context7 or quarkus-agent MCP;" >&2
        echo "  2. prove the deletion is safe with an integration test that drives the real scope" >&2
        echo "     boundary (request-context activate/terminate), not a store/unit-level test;" >&2
        echo "  3. cite the doc + test in the PR body or an ADR." >&2
        echo "See CLAUDE.md §8. This is advisory — the deletion will proceed." >&2
        exit 0
      fi
      ;;
  esac
done

exit 0
