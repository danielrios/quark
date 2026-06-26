---
description: "Adversarial code review by Opus. Cross-model review of Sonnet's implementation against the spec."
allowed-tools: [Read, Bash(git *), Bash(./gradlew *), Bash(cat *), Bash(find *), Bash(ls *), Bash(grep *), Bash(rg *), Bash(wc *), mcp__quarkus-agent__*, mcp__context7__*, Write, Edit]
---

Read these project files first:
- CLAUDE.md (binding contract — especially §6 YAGNI, §7 no drift, §8 lifecycle)
- The spec referenced in $ARGUMENTS (or the latest in `docs/superpowers/specs/`)
- The plan (in `docs/superpowers/plans/`)
- docs/progress.md

## Your task

Perform an adversarial review of the current implementation. You are reviewing
code written by a DIFFERENT model (Sonnet). Your job is to catch what it missed.

### Steps
1. Run `git diff main..HEAD --stat` to see scope of changes
2. Run `git diff main..HEAD` to read the actual diff
3. Read changed files in full for context
4. Run the test gate:
   - Primary: `mcp__quarkus-agent__quarkus_callTool` with `toolName="devui-testing_runTests"`
   - Fallback: `./gradlew test`
5. Run `./gradlew spotlessCheck`

### Write the review
Create: `docs/superpowers/reviews/<date>-<slug>-review.md`

Organize by severity:
1. **🔴 Blockers** — Bugs, spec violations, missing tests, security issues
2. **🟡 Improvements** — YAGNI violations, unnecessary complexity, missing docs
3. **🟢 Nits** — Style, naming, minor cleanup

For each finding:
- Quote the relevant code (file + line)
- Explain the problem clearly
- Suggest a specific fix
- Reference the relevant CLAUDE.md section if applicable

### Verification section
Include test results and spotless output in the review document.

## Rules
- Do NOT fix anything — review only
- Be rigorous: Opus is called specifically because it catches things Sonnet misses
- Check for CLAUDE.md §8 violations (lifecycle components deleted without doc check)
- Check for CLAUDE.md §7 violations (premature abstractions, drifting from destination)
- `git add` and `git commit` the review document
