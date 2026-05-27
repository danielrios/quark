# Architecture Decision Records

This directory holds the load-bearing architectural decisions for `quark`.
Each ADR captures a single decision: the context that forced it, the choice
made, the alternatives considered, and the consequences good and bad. ADRs
are immutable once accepted — when a decision changes, write a new ADR that
supersedes the old one and update the older record's `Status` line.

Format: [Michael Nygard's lightweight ADR template](https://github.com/joelparkerhenderson/architecture-decision-record/blob/main/locales/en/templates/decision-record-template-by-michael-nygard/index.md).

Filename convention: `NNNN-kebab-case-title.md`, where `NNNN` is a
zero-padded sequence number starting at `0001`. Do not reuse numbers.

> Several of these ADRs describe the **destination** architecture, which
> the MVP intentionally does not yet implement. See
> [ADR 0003](0003-walking-skeleton-first-plan-sequencing.md) for the
> sequencing, and the "Today vs Destination" split in
> [`ARCHITECTURE.md`](../../ARCHITECTURE.md) for which decisions are
> active right now.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-event-driven-agentevent-stream.md) | Event-driven `AgentEvent` stream as the runtime contract | Accepted (destination; implemented from Plan 4) |
| [0002](0002-single-quarkus-module-archunit-boundaries.md) | Single Quarkus module, with package boundaries enforced by ArchUnit at the refactor phase | Accepted (single-module now; ArchUnit from Plan 7) |
| [0003](0003-walking-skeleton-first-plan-sequencing.md) | Walking-skeleton-first plan sequencing | Accepted |
| [0004](0004-claude-code-harness.md) | Claude Code harness configuration | Accepted |
| [0005](0005-release-please-automation.md) | Release automation with release-please | Accepted |
