# Brainstorm — Plan 5: NIM provider, `/provider`, `/status`

**Status:** exploration; feeds the Plan 5 spec. Revised after critique round 1 (Fable 5, 2026-07-10 — 15 findings incorporated or answered below).
**Date:** 2026-07-10.
**Inputs:** [ADR 0003](../../adr/0003-walking-skeleton-first-plan-sequencing.md) row 5, [ARCHITECTURE.md](../../../ARCHITECTURE.md) Destination pipeline + boundaries, [vision](../../vision/runtime-platform.md) ("multiple providers behind `ModelGateway`, with per-session preference and failover"), post-Plan-4 source tree.

---

## Why Plan 5 exists (the pressure)

Plan 4 extracted `ModelGateway`, but with one implementation it is a rename,
not an abstraction (MVP spec §3: "if an abstraction has only one
implementation, it probably should not exist yet"). Plan 5 adds the second
implementation — NVIDIA NIM's OpenAI-compatible endpoint — so the SPI is
exercised by real divergence: different wire protocol, different auth,
different failure modes. The user-facing surface (`/provider`, `/status`) is
deliberately thin; the architectural payload is provider *resolution* as a
first-class runtime stage ("resolve provider" in the Destination pipeline —
the one stage Plan 4 left unimplemented).

Not in this plan (vision lists them, later plans own them): failover, provider
health checks, REST exposure of provider choice (Plan 6 gets it via
`TurnRequest.provider`, which already exists).

---

## Q1 — How to talk to NIM

| Option | Trade-off |
|--------|-----------|
| **A. `quarkus-langchain4j-openai` as a named model** (`base-url` → `https://integrate.api.nvidia.com/v1`) | ~Zero client code; reuses the exact `StreamingChatModel` bridge shape Plan 4 proved for Gemini; config-driven; langchain4j stays inside `provider.nim`. |
| B. Thin JAX-RS client + hand-rolled SSE parsing | No new extension, full wire control — but ~200 lines of chat-completions protocol + SSE state machine we then own; duplicates what langchain4j already does; more failure surface than the feature warrants. |

**Recommendation: A.** NIM is OpenAI-compatible by design; the OpenAI extension
with a `base-url` override is the least-power tool that works. B is the
fallback if the doc-check falsifies A (revisit trigger below).

**Doc-check status (verified 2026-07-10 against the extension artifacts'
embedded config documentation, quarkus-langchain4j 1.9.2 — the version the
3.35.4 platform BOM resolves; `docs.quarkiverse.io` is egress-blocked in the
authoring environment, and the embedded
`META-INF/quarkus-config-doc/quarkus-config-model.json` is the same generated
source the site renders):**

- `quarkus.langchain4j.chat-model.provider` — exists (build-time; selects the
  default model's provider when several are on the classpath).
- `quarkus.langchain4j."model-name".chat-model.provider` — exists (named
  models).
- `io.quarkiverse.langchain4j.ModelName` annotation + `NamedConfigUtil` —
  present in `quarkus-langchain4j-core:1.9.2`.
- Provider ids: `ai-gemini` (Gemini deployment processor's
  `ChatModelProviderCandidateBuildItem`), `openai` (OpenAI deployment).
- OpenAI runtime config per named model: `…openai."model-name".base-url`,
  `.api-key` (default `dummy`), `.chat-model.model-name`, `.timeout`
  (**default 10 s — must be raised**: a streamed turn can exceed it; the
  Telegram renderer waits 60 s).
- `io.quarkiverse.langchain4j:quarkus-langchain4j-openai:1.9.2` is on Maven
  Central.

**Still open for implementation-time doc-check:** behavior of the openai
extension when `api-key` resolves blank (`${NVIDIA_API_KEY:}` with the var
unset) — startup validation vs first-call failure. Load-bearing for Q4's
unconfigured-provider story and for test boot (tests must never require real
secrets).

Config sketch (all property names verified above):

```properties
# default model stays Gemini; explicit now that two providers share the classpath
quarkus.langchain4j.chat-model.provider=ai-gemini
# named model "nim" served by the openai provider
quarkus.langchain4j.nim.chat-model.provider=openai
quarkus.langchain4j.openai.nim.base-url=${NIM_BASE_URL:https://integrate.api.nvidia.com/v1}
quarkus.langchain4j.openai.nim.api-key=${NVIDIA_API_KEY:}
quarkus.langchain4j.openai.nim.chat-model.model-name=${NIM_MODEL:meta/llama-3.3-70b-instruct}
quarkus.langchain4j.openai.nim.timeout=60s
quark.provider.default=gemini
# tests must boot without real secrets and see deterministic auth headers
%test.quarkus.langchain4j.openai.nim.api-key=test-key
```

`NimModelGateway` then constructor-injects `@ModelName("nim") StreamingChatModel`
— the same seam `GeminiModelGateway` uses, different bean.

**Naming domains (must be spelled out in ADR 0008):** three name spaces touch
here and only partially coincide — quark gateway ids (`gemini`, `nim`; from
`ModelGateway.name()`, used in commands/preference/config/events),
quarkus-langchain4j *provider ids* (`ai-gemini`, `openai`), and the
langchain4j *named-model* name (`nim` in `quarkus.langchain4j.openai.nim.*`).
The `nim`/`nim` coincidence is deliberate convenience, not one namespace.

## Q2 — How the runtime picks a gateway

`AgentRuntime` currently constructor-injects the sole `ModelGateway`. With two:

| Option | Trade-off |
|--------|-----------|
| **A. `String name()` on the SPI + `@Any Instance<ModelGateway>`** | Runtime iterates CDI-provided gateways, picks by name. No new types, no registry, providers stay ignorant of each other, ArchUnit-clean (runtime sees only the SPI). Adding provider N = drop in a bean. |
| B. Dedicated `ModelGatewayRegistry` bean | A second name→gateway map that CDI already maintains; earns nothing at n=2. |
| C. CDI qualifiers per provider (`@Gemini`, `@Nim`) | Compile-time named injection points in the runtime — the runtime would enumerate concrete providers, exactly what ADR 0002 forbids. |

**Recommendation: A.** One method added to the SPI, demanded by this plan's own
code. `name()` values are lower-case stable ids (`"gemini"`, `"nim"`) — they
appear in config, commands, preference entries, events, and (Plan 7) metric
tags.

**Sequencing constraint (critique finding 1 — would otherwise red the gate):**
the moment a second `@ApplicationScoped ModelGateway` bean exists, every
unqualified `ModelGateway` injection is ambiguous and **deployment of the
whole app fails** — `AgentRuntime`'s own constructor first. Order of work is
therefore: (1) add `name()` to the SPI + `GeminiModelGateway` + test fakes,
(2) switch `AgentRuntime` to `@Any Instance<ModelGateway>` + resolution with
one gateway present, suite green, (3) only then add `NimModelGateway`.
Known touch points that must migrate in step (1)–(2), not after:
`RuntimeWiringTest` (unqualified `@Inject ModelGateway` → `@Any
Instance<ModelGateway>`, asserting both names once NIM lands),
`AgentRuntimeTest.FakeGateway` (gains `name()`), `AgentRuntime` constructor.
The §8 memory backstops (`TelegramConversationMemoryTest`,
`TelegramStreamingMemoryTest`) are *unchanged in intent and text* but must be
re-verified green at each step — their
`QuarkusMock.installMockForType(model, StreamingChatModel.class)` targets the
default (unqualified) model bean, which stays Gemini's; the named
`@ModelName("nim")` bean is a separate bean and unaffected. Confirm at
implementation time.

**Static misconfiguration fails startup, not per-turn (critique finding 7):**
two gateways answering the same `name()`, or `quark.provider.default` naming
no gateway, are startup-detectable facts — a startup observer validates both
and fails fast. Only *dynamic* unknowns (stale preference entry, explicit
`TurnRequest.provider` from a future adapter) surface as `TurnFailed`
(Q4). Explicit over magical, at the right time.

## Q3 — Where preference lives

ARCHITECTURE.md's boundary table already reserves the slot:
`adapter.*` may depend on `memory.preference`; the Destination pipeline reads
"resolve provider (ProviderPreferenceStore)".

- SPI `com.quark.memory.preference.ProviderPreferenceStore`:
  `Optional<String> get(sessionId)` / `void set(sessionId, provider)`.
- `InMemoryProviderPreferenceStore`: `@ApplicationScoped`,
  `ConcurrentHashMap` — the `InMemoryChatMemoryStore` pattern, minus bounding
  (one value per session).
- Writers: the Telegram adapter (`/provider`) — the boundary table explicitly
  permits this; routing writes through the runtime would grow it into a
  settings API for no boundary gain. **Accepted cost (critique finding 12):**
  a second adapter (Plan 6 REST) re-implements the validate-then-write dance;
  "second adapter duplicating preference validation" goes into ADR 0008's
  revisit triggers as the signal to lift a `setProvider` onto the runtime —
  mirroring how ADR 0007 routed `/reset` through the runtime.
- Reader: the runtime's resolve stage.
- Unset sessions fall back to `quark.provider.default=gemini` (config, one
  place, read by the runtime).

**Does `/reset` clear the preference?** No: `/reset`'s contract is "drop
conversation memory"; the provider choice is a setting, not memory. A user
switching to NIM and resetting the chat expects to still be on NIM. (Rejected
alternative: bundling both would make `/reset` semantics grow by side effect.)

## Q4 — Resolution order, unknown names, unconfigured providers

Resolution (in the runtime, per turn):
`TurnRequest.provider` (explicit, already in the Plan 4 contract) →
preference store → `quark.provider.default`.

Normalization: provider arguments are `trim().toLowerCase(Locale.ROOT)`-ed by
the adapter before validation and storage; `name()` ids are lower-case by
contract, so `/provider NIM` works (critique finding 8).

| Case | Behavior |
|------|----------|
| `/provider <bogus>` | Rejected at write time — adapter validates against `runtime.providers()` and replies with the valid names; nothing stored. |
| Dynamic resolution yields a name no gateway carries (stale store entry; explicit `TurnRequest.provider` from a future REST caller) | **`TurnFailed("unknown provider: …")`** — explicit over magical. A silent fallback to Gemini would answer with the wrong model and hide misconfiguration; the event stream is where failures are data (ADR 0001). Event sequence pinned to the existing pre-model failure shape (`AgentRuntime` catch block): `TurnStarted` → `TurnFailed`, resolution happening **before** memory load — no point loading history for a turn that cannot run (critique finding 5). Nothing persisted. |
| Provider is valid but unconfigured — `/provider nim` with `NVIDIA_API_KEY` unset (critique finding 3) | **Accepted as lazy per-turn failure.** Write-time validation checks *existence*, not credentials (a gateway bean is a lazy client proxy; "key present" ≠ "key works" — a bad key 401s identically, so eager validation buys little and adds SPI surface). Each turn then ends in `TurnFailed`; the Telegram renderer shows `ERR_FALLBACK`, never the raw reason (the ADR 0007 sanitization trigger stays with Plan 6). The recovery path must be discoverable: the `/provider nim` confirmation message names the way back (see Q7 reply texts), and `/status` always shows the current provider. Recorded as a decision in ADR 0008, not discovered in production. |

The adapter needs the valid-name set for write-time validation and `/status`:
the runtime exposes it (`Set<String> providers()`), derived from the injected
`Instance<ModelGateway>` — adapters may depend on `runtime`, not on
`provider.*`.

## Q5 — Does `ModelInvoked` learn which provider ran?

Plan 4 shaped events minimally: `ModelInvoked(turnId)`. With a second
provider, "which model answered this turn" becomes a per-turn *variable* —
and the vision's observability principle says the event stream is the
contract, "not instrumentation glued on later"; logs are a projection of it.
The runtime will log the resolved provider either way; leaving it out of the
stream would make the canonical record lie by omission the moment it matters.
That is the justification — observability of the stream itself, now.
(Plan 7's metric tag then *consumes* the field, it does not justify it —
critique finding 9 rightly rejected the original "Plan 7 wants it" framing.)

**Recommendation: `ModelInvoked(turnId, provider)`.** Additive field on one
variant; renderers project via `instanceof` and none reads `ModelInvoked`
fields today (verified: `TelegramStreamHandler` projects only
`TokenEmitted`/`TurnFailed`/`TurnCompleted`; `AgentRuntimeTest` only does
`assertInstanceOf`), so nothing breaks — ADR 0001's stability clause covers
removing/renaming variants, not enriching one under real pressure. Rejected:
a new `ProviderResolved` variant — a resolution that emits its own event on
every turn inflates the stream for a fact `ModelInvoked` can carry.

## Q6 — `/status`: what, and computed where

Content (assignment): uptime, current provider, memory size.

| Fact | Owner | Why |
|------|-------|-----|
| Uptime | Adapter (`Instant` captured at `StartupEvent`, which `TelegramBotRunner` already observes) | Process fact rendered for a chat; no runtime involvement needed. |
| Current provider | Runtime — `resolveProvider(sessionId)` | Resolution precedence must not be duplicated in adapters (would drift). |
| Memory size | Runtime — `messageCount(sessionId)` delegating to the store (named after the `MemoryLoaded.messageCount` event field — critique finding 14) | Adapters may not touch `memory` (chat) per ADR 0002. |

**Precision (critique finding 6):** `resolveProvider(sessionId)` is defined as
"resolution *absent an explicit per-turn override*" — preference → default.
`execute` composes the full precedence (`TurnRequest.provider` first) from the
same internals; the public method is the tail of it. `/status` output is a
snapshot: with a concurrent writer it may differ from what the next turn
resolves. Accepted — the poller is sequential today; Plan 6 concurrency is
the revisit trigger (same pattern as the `InMemoryChatMemoryStore` javadoc).

The command itself stays in the adapter (`dispatch`) — `/status` is a Telegram
projection, not a runtime capability; Plan 6's REST adapter can compose its own
status shape from the same runtime queries. The runtime's public surface grows
by three read methods (`providers()`, `resolveProvider(sessionId)`,
`messageCount(sessionId)`) — queries, not orchestration; `execute`'s contract
is untouched (vision: additive evolution).

## Q7 — Command parsing and reply texts

`TelegramCommands.parse` returns a bare enum; `/provider gemini` needs its
argument. Extend to a single parse pass returning
`record Parsed(Command command, Optional<String> argument)` with
`Command { RESET, PROVIDER, STATUS, CHAT }` — same mention/whitespace/case
handling as today (`/provider@quarkbot nim` works). The argument is the first
token after the command; anything beyond it is ignored (consistent with
`/reset foo` today, which still resets — critique finding 4). Existing callers
migrate mechanically; `TelegramCommandsTest` grows cases instead of changing
old ones.

Defined command surface (critique finding 4 — every case decided):

| Input | Reply |
|-------|-------|
| `/provider` (bare) | Current provider + valid names: `Provider: gemini. Available: gemini, nim.` |
| `/provider nim` | `Provider set to nim. (Configuration is not checked — if turns start failing, /provider gemini switches back.)` |
| `/provider bogus` | `Unknown provider 'bogus'. Available: gemini, nim.` — nothing stored. |
| `/status` | Uptime + provider + memory size, e.g. `Up 3h 12m · provider: nim · memory: 14 messages.` |

`TelegramBotRunner.handle()` currently routes only `RESET` through the
blocking `dispatch` path; `PROVIDER` and `STATUS` join it — the routing
predicate becomes "command ≠ CHAT → dispatch, else stream", and `parse` is
called once per update now that it returns `Parsed` (critique finding 13).

## Q8 — Gateway code duplication

`NimModelGateway` will look ~identical to `GeminiModelGateway` (both wrap a
`StreamingChatModel`, same emitter bridge, same role mapping). Options:

- **A. Accept the duplication (~40 lines).** Two explicit classes, free to
  diverge (NIM error bodies, request options) — divergence is the point of
  Plan 5. Extract a shared bridge only when a third provider lands (rule of
  three).
- B. Shared bridge helper in `com.quark.provider` — puts langchain4j imports
  into the SPI package that Plan 4 deliberately kept langchain4j-free; ADR 0002
  allows langchain4j only in `provider.<name>`.
- C. One configurable gateway class, instantiated twice by a producer — erases
  the per-provider package structure ARCHITECTURE.md prescribes and makes
  divergence a refactor instead of an edit.

**Recommendation: A**, stated in the plan so the duplication is a decision,
not an accident.

## Q9 — Testing strategy

- `InMemoryProviderPreferenceStoreTest` — plain JUnit: get-empty, set/get,
  overwrite, session isolation.
- `NimModelGatewayTest` — plain JUnit with fake `StreamingChatModel`
  (mirrors `GeminiModelGatewayTest`): bridge order/completion/failure, role
  mapping, `name()`.
- **`NimModelGatewayWireMockTest` — `@QuarkusTest` + WireMock** stubbing an
  OpenAI-compatible `POST /v1/chat/completions` SSE stream on localhost;
  overrides `quarkus.langchain4j.openai.nim.base-url` via a
  `QuarkusTestResourceLifecycleManager` **with `restrictToAnnotatedClass =
  true`** (critique finding 11: without it the resource and its config
  override leak into every `@QuarkusTest`; the isolated-restart cost is
  accepted). Asserts `Authorization: Bearer test-key` (deterministic via the
  `%test` property) and token order. This is the test a fake model cannot
  replace: it proves the named-model config, the base-url override, auth
  header, and the extension's SSE parsing — the actual OpenAI-compat wire
  path NIM depends on. Dependency: `org.wiremock:wiremock`
  (testImplementation). (Alternative: the quarkiverse `quarkus-wiremock`
  extension — rejected: dev-service machinery for what one lifecycle manager
  does.)
- `AgentRuntimeTest` additions — resolution precedence (explicit > preference >
  default), unknown provider → `TurnStarted` → `TurnFailed` + zero
  persistence + no gateway call, `ModelInvoked.provider` value; fakes updated
  for `name()`.
- Startup validation tests — duplicate `name()` / unknown default fail
  deployment (Q2).
- `TelegramCommandsTest` additions — `/provider`, `/provider nim`,
  `/provider@bot nim`, `/provider NIM` (normalization), `/provider nim extra`
  (first token wins), `/status`, case/whitespace.
- Adapter dispatch tests — the four reply rows of Q7's table.
- **Composed-path integration test (critique finding 10):** one `@QuarkusTest`
  driving the real seam end-to-end — `dispatch("/provider nim")`, then a chat
  turn, asserting the *nim-side* recording model received the prompt (two
  recording `StreamingChatModel`s: default bean + `@ModelName("nim")` bean via
  `QuarkusMock`). Two green unit tests on either side of a seam don't prove
  the seam (§8's altitude lesson).
- Existing suite: **not "untouched" — unchanged in observable behavior, but
  mechanically migrated where the SPI grows** (critique finding 1): exact list
  in Q2. Memory backstops re-verified green at every step.

## Q10 — Decision records

Provider resolution semantics (precedence, static-fail-startup vs
dynamic-fail-turn, lazy unconfigured-provider failure, preference survives
`/reset`, `name()` as the stable id, the three naming domains, adapter-side
preference writes + their revisit trigger) is architectural surface →
**ADR 0008**. The doc-check citations above land in that ADR (docs-first,
CLAUDE.md §8 spirit — no lifecycle deletion this time, but the same
"documented contract before code" bar for the multi-provider config).

---

## Vision alignment check

- **Streaming-first:** NIM streams through the same `Multi<String>` SPI; no
  blocking path added.
- **Additive evolution:** `execute`'s signature untouched; one SPI method
  (`name()`), one event field (`ModelInvoked.provider`), three runtime query
  methods. No adapter or renderer reshaping.
- **Transport is not the runtime:** `/provider` and `/status` are Telegram
  projections of runtime-owned facts; Plan 6 will project the same facts over
  REST without new runtime work.
- **Explicit over magical:** provider identity flows visibly — config default,
  stored preference, event field; static misconfiguration fails startup;
  dynamic unknowns fail loudly as events.
- **Observable by default:** the stream, not the log line, is the canonical
  record of which provider served a turn.
- **Small systems compose better:** two ~40-line gateways over one premature
  abstraction.

## Revisit triggers

- Implementation-time doc-check falsifies the blank-api-key assumption or the
  named-model wiring (Q1) → fall back to option B (thin client) and record
  why in ADR 0008.
- A third provider → extract the shared `StreamingChatModel` bridge (Q8).
- A second adapter duplicates preference validation (Plan 6) → lift
  `setProvider` onto the runtime (Q3).
- Failover/health checks get scheduled → resolution grows from a name lookup
  into a strategy; that is the point to reconsider `ProviderResolved` events.
- Plan 6 concurrency → `/status` snapshot staleness and preference-store
  races (Q6).
