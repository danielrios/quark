#!/usr/bin/env bash
# Stop hook — advisory only. Warns if edits happened since last commit/PROGRESS.md update.
# The global /root/.claude/stop-hook-git-check.sh already enforces commit/push at exit.

set -u

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
state="${PROJECT_DIR}/.claude/state/last-edit"
progress="${PROJECT_DIR}/PROGRESS.md"

[[ -f "$state" ]] || exit 0

last_edit="$(cat "$state" 2>/dev/null || echo 0)"
[[ "$last_edit" =~ ^[0-9]+$ ]] || exit 0

last_commit=0
if git -C "$PROJECT_DIR" rev-parse HEAD >/dev/null 2>&1; then
  last_commit="$(git -C "$PROJECT_DIR" log -1 --format=%ct 2>/dev/null || echo 0)"
fi

last_progress=0
if [[ -f "$progress" ]]; then
  last_progress="$(stat -c %Y "$progress" 2>/dev/null || stat -f %m "$progress" 2>/dev/null || echo 0)"
fi

if (( last_edit > last_commit )) && (( last_edit > last_progress )); then
  echo "⚠ edits since last commit AND no PROGRESS.md update — CLAUDE.md §2 state rule." >&2
fi

exit 0
