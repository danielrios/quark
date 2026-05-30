# quark

A compact streaming-first runtime for LLM-driven conversation, built on
Quarkus.

The MVP receives a message, calls a model, streams a reply, remembers a
few turns of context, and ships that experience over Telegram and HTTP.

The long-term ambition is a more general agent runtime — tools, planning,
reflection, episodic memory, multi-provider orchestration. That work
intentionally does not start yet. See [`MANIFESTO.md`](MANIFESTO.md) for
the engineering thesis and [`ARCHITECTURE.md`](ARCHITECTURE.md) for both
the current shape and the destination shape.

---

## What's actually here today

A Quarkus 3.35.4 / Java 25 / Gradle project with a running Telegram bot:
message it and Gemini replies (Plan 1 walking skeleton). No memory, no
commands, no streaming yet — those arrive in Plans 2–3.

Implementation order: [`docs/adr/0003-walking-skeleton-first-plan-sequencing.md`](docs/adr/0003-walking-skeleton-first-plan-sequencing.md).
MVP design: [`docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md`](docs/superpowers/specs/2026-05-25-agent-runtime-mvp.md).

---

## MVP scope

### In

Items not yet implemented are marked _(planned)_; see
[`What's actually here today`](#whats-actually-here-today) for the current state.

* Telegram bot via long polling
* Google Gemini via `quarkus-langchain4j-ai-gemini`
* `POST /chat` and `POST /chat/stream` (SSE) _(planned)_
* In-memory bounded conversation history per session
* Streaming token output, with throttled Telegram message edits _(planned)_
* `/reset` Telegram command
* `/start`, `/status` Telegram commands _(planned)_
* Structured logs with per-turn correlation id _(planned)_
* Unit tests covering memory, dispatch, and the Telegram renderer _(planned)_

### Explicitly deferred

These belong to the destination architecture, not the MVP:

* `AgentRuntime` orchestration core
* Typed `Multi<AgentEvent>` runtime contract
* `ModelGateway` provider abstraction
* NVIDIA NIM provider, provider preference store, `/provider` command
* Layered packages (`core/runtime/memory/provider/adapter`)
* ArchUnit-enforced boundaries
* Micrometer metrics, distributed tracing
* Tool calling, planner/executor decomposition, reflection loops
* Episodic memory, vector search, Redis/Postgres backends
* Retry policies, multi-tenancy, webhook Telegram mode

The point of the MVP is to validate the conversational loop end-to-end
before reaching for any of the above.

---

## Run the bot

Set the required environment variables, then start dev mode via the
`quarkus-agent` MCP:

```bash
export GEMINI_API_KEY=your-gemini-api-key
export TELEGRAM_BOT_TOKEN=your-token-from-botfather
```

Start dev mode (Claude Code / MCP):

```
quarkus_start   # via quarkus-agent MCP — never run ./gradlew quarkusDev directly
```

Once running, message your bot in Telegram and it will reply using Gemini.

**Tests require no secrets.** The `%test` profile sets
`quark.telegram.enabled=false` and a dummy Gemini key, so `./gradlew test`
passes without any credentials.

---

## Running (CLI)

```bash
./gradlew build        # build + tests
./gradlew build -Dquarkus.native.enabled=true   # native image
```

Dev UI: <http://localhost:8080/q/dev/>.

> When working through Claude Code, use the `quarkus-agent` MCP
> (`quarkus_start`, `quarkus_status`, `quarkus_logs`) instead of running
> `./gradlew quarkusDev` in the foreground — see
> [`CLAUDE.md`](CLAUDE.md) and [ADR 0004](docs/adr/0004-claude-code-harness.md).

---

## Configuration

Once the MVP lands, these environment variables enable each transport:

```bash
# Gemini
export GEMINI_API_KEY=...

# Telegram (off by default)
export QUARK_TELEGRAM_ENABLED=true
export TELEGRAM_BOT_TOKEN=...
```

---

## Telegram commands

| Command   | Behaviour                                |
| --------- | ---------------------------------------- |
| `/reset`  | clears conversation memory               |
| `/start`  | welcome message _(planned)_              |
| `/status` | uptime + memory size _(planned)_         |

`/provider` is intentionally not part of the MVP. It arrives with the
second provider, alongside the runtime refactor.

---

## Repository layout

```
README.md            — this file
MANIFESTO.md         — engineering philosophy
ARCHITECTURE.md      — today's pipeline + destination shape
CLAUDE.md            — operating rules for Claude Code sessions
docs/
├── adr/             — load-bearing architectural decisions
├── progress.md      — task progress ledger
├── superpowers/
│   ├── specs/       — design specs (MVP design lives here)
│   └── plans/       — incremental implementation plans
└── vision/          — long-term direction narrative
.claude/             — Claude Code harness (hooks, slash commands, settings)
```

---

## Why "quark"

The smallest things, composing into larger structures. Also a nod to
Quarkus.

---

## Status

The repository is in the MVP bootstrap phase. Versioning starts at
`0.0.0` and bumps through [release-please](docs/adr/0005-release-please-automation.md)
once feature commits land on `main`.
