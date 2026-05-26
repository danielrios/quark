#!/usr/bin/env bash
# Stop hook — advisory only. Warns if source edits happened since last commit
# AND the progress ledger has no NEW commit covering them.
# Uses git log (commit time), not file mtime, to defeat `touch progress.md` evasion.
# Global /root/.claude/stop-hook-git-check.sh still enforces commit/push at exit.

set -u

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
state="${PROJECT_DIR}/.claude/state/last-edit"
progress_file="docs/progress.md"

[[ -f "$state" ]] || exit 0

last_edit="$(cat "$state" 2>/dev/null || echo 0)"
[[ "$last_edit" =~ ^[0-9]+$ ]] || exit 0

git -C "$PROJECT_DIR" rev-parse HEAD >/dev/null 2>&1 || exit 0

# Latest commit time on the branch.
last_commit="$(git -C "$PROJECT_DIR" log -1 --format=%ct 2>/dev/null || echo 0)"

# Latest commit time of the progress file specifically — defeats a bare
# `touch docs/progress.md` because that does not change the commit time.
last_progress="$(git -C "$PROJECT_DIR" log -1 --format=%ct -- "$progress_file" 2>/dev/null || echo 0)"

if (( last_edit > last_commit )) && (( last_edit > last_progress )); then
  echo "⚠ source edits since last commit AND no committed update to ${progress_file} — CLAUDE.md §2 state rule." >&2
fi

exit 0
