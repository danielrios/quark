# Architecture Decision Records

This directory holds the load-bearing architectural decisions for `quark`.
Each ADR captures a single decision: the context that forced it, the choice
made, the alternatives considered, and the consequences good and bad. ADRs
are immutable once accepted — when a decision changes, write a new ADR that
supersedes the old one and update the older record's `Status` line.

Format: [Michael Nygard's lightweight ADR template](https://github.com/joelparkerhenderson/architecture-decision-record/blob/main/locales/en/templates/decision-record-template-by-michael-nygard/index.md).

Filename convention: `NNNN-kebab-case-title.md`, where `NNNN` is a
zero-padded sequence number starting at `0001`. Do not reuse numbers.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-event-driven-agentevent-stream.md) | Event-driven `AgentEvent` stream as the runtime contract | Accepted |
| [0002](0002-single-quarkus-module-archunit-boundaries.md) | Single Quarkus module with package boundaries enforced by ArchUnit | Accepted |
| [0003](0003-walking-skeleton-first-plan-sequencing.md) | Walking-skeleton-first plan sequencing | Accepted |
