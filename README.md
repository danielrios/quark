# quark

An event-driven agent runtime built on Quarkus.

The codebase is in early bootstrap: the runtime itself is not implemented
yet. The current state is a fresh Quarkus 3.35.4 / Java 25 skeleton
generated from `code.quarkus.io` with `langchain4j-core` on the classpath.

## Design

The slice 1 design lives at
[`docs/superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md`](docs/superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md).

In short, slice 1 ships an observable, streaming-first execution runtime:

- `AgentRuntime` orchestration core emitting a typed `Multi<AgentEvent>`.
- Two transport adapters: REST + SSE, and Telegram (long-polling).
- Two `ModelGateway` implementations: Google Gemini and NVIDIA NIM (via
  the OpenAI-compatible API).
- In-process working memory and provider preference store, both behind
  SPIs designed to swap to Redis/Postgres in later slices.
- ArchUnit-enforced package boundaries.

Tools, planning, episodic memory, procedural memory, and the async
reflection pipeline are explicit non-goals of slice 1 and are deferred
to later slices that extend the runtime additively.

## Running

Dev mode (live reload):

```shell
./gradlew quarkusDev
```

Build:

```shell
./gradlew build
```

Native build:

```shell
./gradlew build -Dquarkus.native.enabled=true
```

The Dev UI is available at <http://localhost:8080/q/dev/> while in dev
mode.

## Configuration

Provider credentials and the Telegram bot token are read from the
environment. See the design spec, §5 (`Configuration`), for the full
property list. The minimum to run with one provider:

```shell
export GEMINI_API_KEY=...
./gradlew quarkusDev
```

Telegram is disabled by default. Enable it with:

```shell
export QUARK_TELEGRAM_ENABLED=true
export TELEGRAM_BOT_TOKEN=...
```
