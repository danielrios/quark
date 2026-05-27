# 0005 — Release automation with release-please

**Status:** Accepted, 2026-05-26
**Deciders:** Daniel + harness-engineering session
**Context refs:** [`.github/workflows/release-please.yml`](../../.github/workflows/release-please.yml), [`release-please-config.json`](../../release-please-config.json), [ADR 0004](./0004-claude-code-harness.md)

---

## Context

ADR 0004 closed the loop on contributor ergonomics, CI enforcement, and
Claude Code harness behavior. What it did *not* solve: how changes on
`main` become versioned releases.

Before this ADR:

* merges to `main` produced no tags,
* no release cadence existed,
* no changelog was generated,
* GitHub Releases were manual/nonexistent,
* version progression was implicit and invisible.

The repository already follows Conventional Commits:

```text
feat:
fix:
refactor:
docs:
ci:
chore:
```

That makes semantic version automation viable with little additional
process cost.

Two release strategies were evaluated.

### Option 1 — Tag on every merge

Every push to `main` automatically bumps and tags a version.

Pros:

* simple,
* minimal tooling.

Cons:

* noisy release history,
* documentation and CI changes generate releases,
* versions track commit count rather than meaningful functionality,
* poor signal-to-noise ratio during the walking-skeleton phase.

### Option 2 — Release PR model

A bot continuously maintains a single release PR containing:

* version bump,
* changelog entries,
* release notes.

Merging feature PRs only accumulates release candidates.
Merging the release PR performs the release.

This introduces a small amount of ceremony, but keeps releases aligned
with meaningful change.

---

## Decision

Adopt **release-please** using:

```text
googleapis/release-please-action@v4
```

The project uses the "release PR" model.

---

## Configuration

### Repository shape

Single-package repository:

```json
{
  "packages": {
    ".": {
      "release-type": "simple"
    }
  }
}
```

### Manifest

`.release-please-manifest.json`

```json
{
  ".": "0.0.0"
}
```

The repository remains silent until at least one release-worthy commit
(`feat:`, `fix:`, or `BREAKING CHANGE:`) lands on `main`.

---

### Gradle integration

`build.gradle.kts` contains:

```kotlin
version = "0.0.0" // x-release-please-version
```

`release-please` updates this automatically through `extra-files`.

---

### Changelog policy

Included in generated changelog:

* `feat`
* `fix`
* `perf`
* `refactor`

Hidden:

* `docs`
* `ci`
* `chore`
* `test`
* `build`

The changelog is intended to represent runtime evolution, not repository
maintenance noise.

---

### Pre-1.0 semantics

Configured:

```json
"bump-minor-pre-major": true
```

Meaning:

| Commit type        | Version bump before 1.0 |
| ------------------ | ----------------------- |
| `fix:`             | patch                   |
| `feat:`            | minor                   |
| `BREAKING CHANGE:` | major                   |

This avoids meaningless major-version churn during the exploratory
walking-skeleton phase.

---

## Workflow

`release-please.yml`

Trigger:

```yaml
on:
  push:
    branches:
      - main
```

Permissions:

```yaml
contents: write
pull-requests: write
```

Responsibilities:

* create/update release PR,
* generate changelog,
* create GitHub Release,
* create git tag.

The workflow intentionally does **not** run tests or formatting.

Those remain the responsibility of `ci.yml`.

The release PR must pass normal CI before merge.

---

## Consequences

### Positive

* Release state is always visible through the open Release PR.
* CHANGELOG generation becomes deterministic and mechanical.
* Semantic versioning is enforced consistently.
* Git tags align with actual released commits.
* Documentation and infrastructure churn no longer inflate version
  numbers.
* GitHub Releases become a natural extension of merge flow instead of
  an afterthought.

---

### Negative

* Release flow now requires two PRs:

  1. feature/change PR,
  2. release PR.
* Conventional Commit discipline becomes operationally important.
* Local builds between releases are versioned using the previous release
  plus git metadata rather than `-SNAPSHOT`.

---

## Mitigations

* CI validates the generated Release PR before merge.
* Incorrect proposed bumps can be overridden using:

```text
Release-As: x.y.z
```

in a commit footer.

* Tags use the format:

```text
vX.Y.Z
```

which keeps:

```bash
git describe
```

useful during local development.

---

## Alternatives rejected

### Manual releases

Rejected because:

* release metadata drifts,
* changelog discipline decays,
* tags become inconsistent,
* high operational friction for little value.

### Auto-tag every merge

Rejected because:

* versions become noisy,
* non-functional commits produce releases,
* release history loses meaning during rapid iteration.

---

## Revisit if

Revisit this ADR if:

* the project begins publishing Maven artifacts,
* Conventional Commit drift becomes common,
* the repository splits into multiple releasable modules,
* prerelease channels (`alpha`, `beta`, `rc`) become necessary,
* release cadence becomes significantly higher than current expectations.
