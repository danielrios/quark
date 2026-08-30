---
description: "Adversarial cross-model review of the branch changes. Opus-tier: catches what the implementer missed."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(./gradlew *), Bash(cat *), Bash(find *), Bash(ls *), Bash(grep *), Bash(rg *), mcp__quarkus-agent__*, mcp__context7__*]
---

Conduct an adversarial review of the work implemented against the spec: $ARGUMENTS

Read first: CLAUDE.md (especially §8 lifecycle discipline), the spec in
$ARGUMENTS, the plan, and `git diff main...HEAD`.

## Skills to use
- `superpowers:requesting-code-review` + `superpowers:receiving-code-review` —
  run the review rigorously; verify claims rather than rubber-stamping.
- `codebase-design` — judge module depth/seams, not just line-level nits.
- `caveman` (caveman mode) — compress prose; keep full technical accuracy.

## Output contract (the simplify phase depends on this)
Write the review to `docs/superpowers/reviews/<date>-<slug>-review.md`, with the
**same `<date>-<slug>`** as the spec so `/simplify` finds it. Group findings as:
- 🔴 **Blockers** — must fix (correctness, lifecycle §8, security).
- 🟡 **Improvements** — align with CLAUDE.md §6 (YAGNI/KISS).
- 🟢 **Nits** — readability only.

`git add` + `git commit` the review document. Do not fix anything here — review
only; `/simplify` actions the findings.
