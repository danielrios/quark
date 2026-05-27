# 0005 — Release automation with release-please

**Status:** Accepted, 2026-05-26
**Deciders:** Daniel + harness-engineering session
**Context refs:** [`.github/workflows/release-please.yml`](../../.github/workflows/release-please.yml), [`release-please-config.json`](../../release-please-config.json), [ADR 0004](./0004-claude-code-harness.md)

## Context

The harness landed in ADR 0004 closed the loop on enforcing CLAUDE.md
rules and running CI on PRs. What it did not address: how does a merge
to `main` turn into a tagged, released version? Until now, every merge
silently advanced `main` with no version bump, no tag, no changelog,
and no GitHub Release.

The project already uses Conventional Commits (`chore(harness):`,
`ci(harness):`, `docs(plan):`, `feat:`, `fix:`, etc. — see `git log`).
That convention pairs naturally with automated release tooling that
can compute semver bumps from commit prefixes.

Two viable shapes for "release/tags auto post-merge":

1. **Tag-on-every-merge.** A small workflow that runs on push to
   `main`, bumps patch (or reads a label), tags the commit. Simple but
   noisy: every chore/docs PR cuts a release, version numbers race
   ahead of meaningful change.
2. **Release-please managed release PR.** A workflow that, on every
   push to `main`, opens (or updates) a single "Release PR" containing
   the version bump and CHANGELOG entries for accumulated commits.
   Merging that PR is the act of cutting a release; merging feature
   PRs just queues up changelog entries.

## Decision

Adopt **release-please** (`googleapis/release-please-action@v4`).

### Configuration

- **`release-please-config.json`** — single package at repo root, type
  `simple`, package-name `quark`, tags formatted `vX.Y.Z` (default
  Conventional Commits semver).
- **`.release-please-manifest.json`** — single entry `{".": "0.0.0"}`.
  First release-please run will only open a release PR once a `feat:`,
  `fix:`, or `BREAKING CHANGE:` commit accumulates on `main`. Until
  Plan 1 ships, the workflow is silent.
- **`build.gradle.kts`** — version annotated with the marker comment:
  `version = "0.0.0" // x-release-please-version`. release-please's
  `extra-files: [{ type: "generic", path: "build.gradle.kts" }]` keeps
  this in sync with the manifest on every release.
- **Changelog filtering** — `feat`, `fix`, `perf`, `refactor` surface
  in the changelog. `chore`, `ci`, `docs`, `test`, `build` are hidden.
  This keeps release notes focused on user-visible change.
- **Pre-1.0 behavior** — `bump-minor-pre-major: true` so `feat:` bumps
  minor (0.1.0 → 0.2.0) instead of major during the walking-skeleton
  phase. First major bump happens explicitly when the project is ready
  for 1.0.

### Workflow shape

- `release-please.yml` runs on `push: main` only.
- Permissions scoped to `contents: write` + `pull-requests: write`
  (needs to push tags + create PRs).
- Concurrency group prevents races if main moves twice quickly.
- No build/test steps in this workflow — `ci.yml` already covers that
  on the Release PR before it can be merged.

## Consequences

### Positive
- Every merge to `main` produces or updates a Release PR. The author
  of that PR (release-please-bot) is visible in the PR list, so the
  state of "what will the next release contain" is always queryable.
- CHANGELOG.md is generated and maintained mechanically. No manual
  curation, no drift between the changelog and `git log`.
- Tags follow semver and live on the commit that contains the version
  bump. GitHub Releases are created automatically with notes pulled
  from the Conventional Commits summary.
- Reverts and amendments to the Release PR are normal PR operations —
  no special tooling.
- Chore/docs commits no longer trigger releases. Version numbers track
  user-visible change, not commit count.

### Negative
- Two PRs per release cycle: the feature PR(s), then the release PR.
  For a single-developer walking-skeleton project this is mild ceremony
  but real.
- Requires Conventional Commits compliance. A non-conformant commit
  message lands as "chore" by default — silently absent from the
  changelog. CLAUDE.md §6 already prefers tight commit hygiene; we
  rely on review, not enforcement.
- `build.gradle.kts` version stays at the last released version
  between releases (not Maven-style `-SNAPSHOT`). Local dev builds are
  identified by the last release tag + git hash, not a SNAPSHOT
  suffix. Acceptable for this project; reconsider if the Maven publish
  path becomes a requirement (Plan 7+).

### Mitigations
- The Release PR is itself subject to `ci.yml` (Spotless + test), so
  no broken release can ship even if release-please's bump is wrong.
- Tags follow `vX.Y.Z` so `git describe` returns meaningful output
  even between releases.
- If release-please proposes a bump we disagree with, override via the
  `Release-As: x.y.z` footer in the next commit message.

## Revisit if

- The project needs Maven publishing — add Spotless + signing + a
  `publish` step gated on the release tag.
- Conventional Commit compliance becomes a problem — add
  `wagoid/commitlint-github-action` on PRs (deferred today per the
  earlier advisor review, which argued tooling overhead exceeded value
  for a single-developer project).
- We start needing per-component releases (e.g. separate runtime vs.
  adapter modules in plan 6/7) — switch release-please from `simple`
  to multi-package mode with one entry per module.
- A release-please bump is consistently wrong (e.g. it ships
  prereleases without a clear path back to stable) — reconfigure or
  pin a specific bump strategy via `release-as` overrides.
