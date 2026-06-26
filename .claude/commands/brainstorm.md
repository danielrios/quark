---
description: "Brainstorm approaches for a feature. Opus-tier: explores trade-offs, risks, and recommendations."
allowed-tools: [Read, Edit, Write, Bash(git *), Bash(cat *), Bash(find *)]
---

Read these project files first:
- CLAUDE.md (binding contract)
- ARCHITECTURE.md (current + destination architecture)
- MANIFESTO.md (engineering philosophy)
- docs/progress.md (current state)

## Your task

Brainstorm approaches for the feature described in the user's message ($ARGUMENTS).

Create a brainstorm document at: `docs/superpowers/brainstorms/<date>-<slug>.md`

Structure:
1. **Problem statement** — one paragraph framing the need
2. **Approach A / B / C** — three distinct approaches, each with:
   - Description
   - Pros
   - Cons
   - Estimated complexity (S/M/L)
3. **Recommendation** — which approach and why
4. **Risks and mitigations**
5. **Dependencies** on existing code (reference specific files/classes)

## Rules
- Follow MANIFESTO.md philosophy (explicit > magical, streaming-first, additive evolution)
- Reference ADRs where design decisions apply
- Do NOT implement anything — this is brainstorm only
- `git add` and `git commit` the brainstorm document
