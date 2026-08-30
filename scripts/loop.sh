#!/usr/bin/env bash
# =============================================================================
# loop.sh — launch the multi-model loop container WITHOUT docker compose
# =============================================================================
# docker compose v2 isn't guaranteed to be installed; this wrapper drives the
# same container as docker-compose.claude.yml with a plain `docker run`.
#
# Usage:
#   scripts/loop.sh run --feature "Plan 3 — Telegram streaming" [orchestrate args]
#   scripts/loop.sh exec  <command...>      # one-shot command in the container
#   scripts/loop.sh shell                   # interactive bash
#
# Mounts (mirrors docker-compose.claude.yml + the skills fix):
#   <repo>        -> /workspace
#   ~/.claude     -> /home/claude/.claude   (auth, plugins, skill symlinks)
#   ~/.agents     -> /home/claude/.agents   (symlink targets for personal skills)
#   named caches  -> ~/.gradle, ~/.jbang
#
# See: docs/adr/0007-multi-model-loop-harness.md
# =============================================================================

set -euo pipefail

IMAGE="${LOOP_IMAGE:-claude-loop:latest}"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# Personal skills (caveman, cavecrew, Matt Pocock engineering skills) are
# symlinked from ~/.claude/skills into ~/.agents/skills. Without the second
# mount those symlinks dangle in the container and the skills silently vanish.
if [[ ! -d "${HOME}/.agents" ]]; then
  echo "⚠ ${HOME}/.agents not found — caveman/cavecrew/pocock skills will be missing in-container." >&2
fi

# Personal skills (~/.claude/skills/*) are *registered* via ~/.claude.json, not
# merely by scanning the dir — without it the skills are invisible even though
# the files mount in. Build an ephemeral copy that keeps the host's skill
# registration but trusts /workspace (so settings.json permissions aren't
# ignored). The host file is never mutated; the temp copy is discarded on exit.
CLAUDE_JSON_MOUNT=()
if [[ -f "${HOME}/.claude.json" ]] && command -v python3 >/dev/null 2>&1; then
  MERGED_CLAUDE_JSON="$(mktemp -t quark-loop-claude.XXXXXX.json)"
  trap 'rm -f "${MERGED_CLAUDE_JSON}"' EXIT
  if python3 - "${HOME}/.claude.json" "${MERGED_CLAUDE_JSON}" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
d = json.load(open(src))
d.setdefault("projects", {}).setdefault("/workspace", {})["hasTrustDialogAccepted"] = True
json.dump(d, open(dst, "w"))
PY
  then
    CLAUDE_JSON_MOUNT=(-v "${MERGED_CLAUDE_JSON}:/home/claude/.claude.json:z")
  else
    echo "⚠ could not build merged ~/.claude.json — personal skills may be missing in-container." >&2
  fi
else
  echo "⚠ ~/.claude.json or python3 missing — personal skills may be missing in-container." >&2
fi

docker_run() {
  local interactive="$1"; shift
  local tty_flags=()
  [[ "$interactive" == "tty" ]] && tty_flags=(-it)
  docker run --rm "${tty_flags[@]}" \
    --user claude \
    -v "${REPO_ROOT}:/workspace:z" \
    -v "${HOME}/.claude:/home/claude/.claude:z" \
    -v "${HOME}/.agents:/home/claude/.agents:z" \
    "${CLAUDE_JSON_MOUNT[@]}" \
    -v quark-gradle-cache:/home/claude/.gradle \
    -v quark-jbang-cache:/home/claude/.jbang \
    -e ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-}" \
    -e CONTEXT7_API_KEY="${CONTEXT7_API_KEY:-}" \
    -e BASH_MAX_OUTPUT_LENGTH=15000 \
    -e CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    -e TZ="${TZ:-America/Sao_Paulo}" \
    -w /workspace \
    "$@"
}

cmd="${1:-run}"; shift || true
case "$cmd" in
  run)
    # Forward args to the orchestrator inside the container.
    docker_run notty --entrypoint /bin/bash "$IMAGE" scripts/orchestrate.sh "$@"
    ;;
  exec)
    # One-shot: scripts/loop.sh exec claude -p "..." --model sonnet ...
    docker_run notty --entrypoint "$1" "$IMAGE" "${@:2}"
    ;;
  shell)
    docker_run tty --entrypoint /bin/bash "$IMAGE"
    ;;
  *)
    echo "Usage: scripts/loop.sh {run <orchestrate args>|exec <command...>|shell}" >&2
    exit 1
    ;;
esac
