# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

`quark` is an event-driven agent runtime built on Quarkus. **The runtime itself is not yet implemented.** The repository currently contains:

- A fresh Quarkus 3.35.4 / Java 25 skeleton (one `GreetingResource` at `GET /hello`).
- `quarkus-langchain4j-core` on the classpath, but no provider extension wired yet.
- A complete **slice 1 design spec** that is the long-term north star.
- A sequence of small **implementation plans** that grow the codebase toward the spec additively.

Before writing code, read the relevant design/plan documents — they define the architecture and constrain what should and shouldn't be built next.

## Required reading before any non-trivial change

1. **`docs/superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md`** — the slice 1 design. Defines `AgentRuntime`, `AgentEvent`, `ModelGateway`, `ChatMemoryStore`, `ProviderPreferenceStore`, the REST/SSE and Telegram adapters, the package boundaries, and the error model. Treat this as the destination, not the starting state.
2. **`docs/superpowers/plans/`** — ordered implementation plans. Plan 1 (Telegram + Gemini walking skeleton) is what's being built now. Plans 2–7 add memory, streaming, abstractions, more providers, REST/SSE, and observability respectively. **Do not skip ahead** — each plan deliberately defers abstractions until enough code exists to justify them.

If asked to implement something that isn't in the current plan, check whether it belongs to a later plan and surface that before doing the work.

## Tooling

### Quarkus Agent MCP is the primary toolchain

Any Quarkus task — searching docs, creating projects, running tests, adding extensions, viewing the Dev UI, restarting the app — **goes through the `quarkus-agent` MCP server**, not through `mvn`/`gradle` commands or generic web search.

- Docs: `quarkus_searchDocs`
- Dev mode lifecycle: `quarkus_start`, `quarkus_stop`, `quarkus_restart`, `quarkus_status`, `quarkus_logs`
- Tests: `quarkus_callTool` with `toolName: "devui-testing_runTests"` (all) or `"devui-testing_runTest"` with `toolArguments: '{"className":"com.quark.Foo"}'` (one)
- Extensions: `quarkus_searchTools` to discover, `quarkus_callTool` to invoke
- Before writing code for an unfamiliar extension: `quarkus_skills` for extension-specific patterns

**Never run `gradle clean` (or `gradle build` of any kind) while Quarkus dev mode is running** — it deletes `build/test-classes` and breaks the in-process test runner. If the test runner gets stuck (`"Tests already in progress"`), do a full `quarkus_stop` + `quarkus_start` cycle.

### Plain Gradle (when not using the MCP)

```shell
./gradlew quarkusDev        # dev mode with live reload, Dev UI at /q/dev/
./gradlew build             # full build
./gradlew test              # all tests
./gradlew test --tests com.quark.DispatcherTest    # single test class
./gradlew build -Dquarkus.native.enabled=true      # native build
```

Always use the wrapper (`./gradlew`), never system Gradle — the wrapper pins the Gradle version.

## Workflow conventions

Specs and plans live under `docs/superpowers/`:

- `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` — designs produced by the brainstorming skill.
- `docs/superpowers/plans/YYYY-MM-DD-<topic>.md` — TDD-styled plans produced by the writing-plans skill.

New features go through brainstorming → spec → plan → execution. Don't jump straight to code for anything non-trivial.

Commits are kept small and logically grouped (`chore:`, `feat:`, `docs:`, `test:`, `docs(spec):`, `docs(plan):` prefixes). One concern per commit.

## Stack notes

- **Java 25** (`sourceCompatibility = JavaVersion.VERSION_25` in `build.gradle.kts`). Virtual threads are first-class.
- **Quarkus 3.35.4**, Gradle Kotlin DSL.
- **langchain4j** via the Quarkiverse extensions. `langchain4j-bom` is enforced; pick provider artifacts (`quarkus-langchain4j-ai-gemini`, `quarkus-langchain4j-openai`, etc.) from that BOM.
- **Package root**: `com.quark`. (Gradle `group` is still the generator's default `org.acme` — discordant but out of scope for current plans.)
- **NVIDIA NIM**: uses the OpenAI-compatible API. Wire via `quarkus-langchain4j-openai` with `quarkus.langchain4j.openai.base-url` pointed at the NIM endpoint — there is no dedicated NIM extension.

## Secrets and local config

- `.ai/`, `.roo/`, `.claude/`, `.quarkus/` are **gitignored** because they may carry MCP configs with API keys. Never commit anything from these directories.
- All credentials come from environment variables (`GEMINI_API_KEY`, `NIM_API_KEY`, `TELEGRAM_BOT_TOKEN`, etc.). Properties in `application.properties` use `${ENV_VAR:default}` interpolation.
- If asked to add an integration, default to env-var-backed config; never bake a literal key into a tracked file.
