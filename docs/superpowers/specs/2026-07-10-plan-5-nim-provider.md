# Spec — Plan 5: NIM provider, per-session preference, `/provider` + `/status`

**Status**: design, awaiting implementation plan.
**Date**: 2026-07-10.
**Project**: quark (Quarkus 3.35.4, Java 25, quarkus-langchain4j 1.9.2).
**Derived from**: [brainstorm 2026-07-10](../brainstorms/2026-07-10-plan-5-nim-provider-brainstorm.md) (options and rejected alternatives live there; this document is normative).
**Governing docs**: ADR 0001 (event contract), ADR 0002 (boundaries), ADR 0003 (row 5), ADR 0007 (memory ownership); decisions here to be recorded as **ADR 0008**.

---

## 1. Goal

Add a second model provider — NVIDIA NIM via its OpenAI-compatible chat
completions endpoint — behind the existing `ModelGateway` SPI, with
per-session provider preference and two Telegram commands (`/provider`,
`/status`). The runtime's "resolve provider" pipeline stage (the one
Destination stage Plan 4 left unimplemented) becomes real.

## 2. Scope

**In:** `NimModelGateway` (`provider.nim`), `ModelGateway.name()`,
`ProviderPreferenceStore` SPI + in-memory impl (`memory.preference`),
provider resolution in `AgentRuntime` (explicit → preference → default),
startup validation of static provider config, `ModelInvoked` gains
`provider`, `/provider` and `/status` Telegram commands, runtime query
methods (`providers()`, `resolveProvider(sessionId)`,
`messageCount(sessionId)`), WireMock-backed wire test, ADR 0008.

**Out (owner):** failover & provider health checks (future; vision),
REST/SSE exposure (Plan 6 — `TurnRequest.provider` already carries the seam),
ArchUnit/Micrometer (Plan 7), sanitizing `TurnFailed.reason` for external
clients (Plan 6 per ADR 0007 trigger), persistent preference storage
(future), per-provider system prompts or model parameters (not demanded).

## 3. Contracts

### 3.1 `ModelGateway` (provider SPI) — grows one method

```java
public interface ModelGateway {
    /** Stable lower-case provider id: "gemini", "nim". Appears in config,
        commands, preference entries, AgentEvents, and (Plan 7) metric tags. */
    String name();
    Multi<String> stream(List<ChatMessage> history);
}
```

### 3.2 `ProviderPreferenceStore` (new SPI, `com.quark.memory.preference`)

```java
public interface ProviderPreferenceStore {
    Optional<String> get(String sessionId);
    void set(String sessionId, String provider);
}
```

`InMemoryProviderPreferenceStore`: `@ApplicationScoped`,
`ConcurrentHashMap<String, String>`. No bound (one value per session). No
`delete` — nothing needs it yet (`/reset` does **not** clear preference:
provider choice is a setting, not conversation memory).

### 3.3 `AgentRuntime` — resolution + queries

- Constructor changes to `(ChatMemoryStore, @Any Instance<ModelGateway>,
  ProviderPreferenceStore, @ConfigProperty("quark.provider.default") String)`.
- **Resolution precedence (per turn):** `TurnRequest.provider` → preference
  store → `quark.provider.default`. The resolved name selects the gateway
  from the injected `Instance`.
- **Dynamic unknown name** (stale preference entry, explicit
  `TurnRequest.provider` naming no gateway): the turn emits
  `TurnStarted` → `TurnFailed("unknown provider: <name>")` and completes —
  resolution happens **before** memory load; nothing persisted, gateway never
  invoked. (Matches the existing pre-model failure shape.)
- **Static misconfiguration fails startup:** a `StartupEvent` observer
  verifies (a) no two gateways share a `name()`, (b)
  `quark.provider.default` names an existing gateway. Violation → startup
  failure with an explicit message. (`@PostConstruct` is NOT sufficient: the
  bean is `@ApplicationScoped`, hence lazy — validation would defer to the
  first message, exactly the behavior this contract forbids. The observer
  also forces eager instantiation.)
- **New query methods** (adapters may depend on `runtime`, not on
  `provider.*` or `memory` chat — ADR 0002):
  - `SortedSet<String> providers()` — the gateway names, lexicographically
    sorted (bean iteration order is unspecified; sorted output makes every
    rendered name list deterministic and exact-string-testable).
  - `String resolveProvider(String sessionId)` — resolution *absent an
    explicit per-turn override* (preference → default); `execute` composes
    the full precedence from the same internals. Returns the stored name
    without validity checking (validity is enforced at write time and by
    `execute`). Snapshot semantics: may differ from the next turn's
    resolution under concurrent writes — accepted while the poller is
    sequential; Plan 6 is the revisit trigger.
  - `int messageCount(String sessionId)` — chat-store size (named after
    `MemoryLoaded.messageCount`).
- **`TurnRequest.provider` hygiene:** the runtime compares names
  case-sensitively; normalization is an adapter contract (§3.6 defines
  Telegram's; Plan 6's REST adapter defines its own). A present-but-blank
  value is treated as a literal unknown name → `TurnFailed` (explicit over
  magical, not silently "no preference").
- `execute` contract otherwise untouched (signature, event guarantees,
  persistence rules of ADR 0007).

### 3.4 `AgentEvent.ModelInvoked` — gains the provider

```java
record ModelInvoked(String turnId, String provider) implements AgentEvent {}
```

Justification (normative): with two providers, "which provider served this
turn" is a per-turn variable; the event stream is the canonical observable
record ("observable by default" — instrumentation is not glued on later).
Verified non-breaking: no renderer or test reads `ModelInvoked` fields;
ADR 0001's stability clause covers removing/renaming variants. The runtime
also logs the resolved provider with the `turnId`.

### 3.5 `NimModelGateway` (`com.quark.provider.nim`)

Constructor-injects `@ModelName("nim") StreamingChatModel`; `name()` returns
`"nim"`; same emitter-bridge and role-mapping shape as `GeminiModelGateway`
including its documented cancellation semantics (detach, no abort). The
~40-line duplication with the Gemini gateway is a **decision** (divergence is
the point; extract a shared bridge at the third provider, not before).
langchain4j imports stay confined to `provider.gemini` + `provider.nim`
(ADR 0002).

### 3.6 Telegram commands

`TelegramCommands.parse(String)` returns
`record Parsed(Command command, Optional<String> argument)`;
`Command { RESET, PROVIDER, STATUS, CHAT }`. Mention (`/cmd@bot`), case, and
whitespace handling as today. `argument` = first token after the command
word, further tokens ignored (consistent with `/reset foo` today);
`Optional.empty()` for CHAT and for commands with no token after the command
word. Provider arguments are normalized `trim().toLowerCase(Locale.ROOT)` by
the adapter before validation and storage.

`TelegramBotRunner.handle()` routes `command != CHAT` through the blocking
`dispatch` path (parse once per update); CHAT streams as today.

Reply table (normative, exact strings; `<names>` = `providers()` joined with
`", "` — already sorted):

| Input | Reply |
|-------|-------|
| `/provider` | `Provider: <resolved>. Available: <names>.` |
| `/provider <valid ≠ default>` (e.g. `nim`) | `Provider set to nim. (Configuration is not checked — if turns start failing, /provider gemini switches back.)` — the parenthetical hint names the configured default and is appended only when the chosen name differs from it. |
| `/provider <default>` (e.g. `gemini`) | `Provider set to gemini.` — no hint (a self-referential "switch back to gemini" would be nonsense). |
| `/provider bogus` | `Unknown provider 'bogus'. Available: <names>.` — nothing stored. |
| `/status` | `Up <uptime> · provider: <resolved> · memory: <n> messages.` |
| `/reset` | Unchanged (`Memory cleared. Starting fresh.`); preference untouched. |

**Uptime rendering (normative):** `<uptime>` = `formatUptime(Duration)` in
`TelegramMessages` — units `d`/`h`/`m`, larger units omitted while zero,
minutes always shown: `0m`, `12m`, `3h 12m`, `2d 0h 5m`. The adapter captures
the start `Instant` at `StartupEvent` in a package-private seam the dispatch
test can set, so `/status` is exact-string-testable. All replies go through
`clampToTelegramLimit` like existing command replies.

**Unconfigured-but-valid provider is a lazy failure (decision):**
`/provider nim` with `NVIDIA_API_KEY` unset succeeds at write time (existence
is validated, credentials are not — a bad key fails identically to a missing
one, only later). Subsequent turns end in `TurnFailed`; the Telegram renderer
shows `ERR_FALLBACK` (raw reasons never reach chat). Recovery is
discoverable: the confirmation text above names the way back; `/status`
always shows the current provider.

### 3.7 Configuration

```properties
# --- Providers (Plan 5) ---
# default model stays Gemini; explicit now that two providers share the classpath
quarkus.langchain4j.chat-model.provider=ai-gemini
# named model "nim" served by the openai provider (OpenAI-compatible NIM endpoint)
quarkus.langchain4j.nim.chat-model.provider=openai
quarkus.langchain4j.openai.nim.base-url=${NIM_BASE_URL:https://integrate.api.nvidia.com/v1}
# fallback matches the extension's own documented default — never resolves to
# *empty* (the one state with unverified boot behavior); a real key comes from env
quarkus.langchain4j.openai.nim.api-key=${NVIDIA_API_KEY:dummy}
quarkus.langchain4j.openai.nim.chat-model.model-name=${NIM_MODEL:meta/llama-3.3-70b-instruct}
# default client timeout is 10s — a streamed turn can exceed it; renderer waits 60s
quarkus.langchain4j.openai.nim.timeout=60s
quark.provider.default=gemini
# tests must boot without real secrets and see deterministic auth headers
%test.quarkus.langchain4j.openai.nim.api-key=test-key
```

All property names, the provider ids (`ai-gemini`, `openai`), `@ModelName`,
and the openai artifact's availability were verified against the extensions'
embedded config documentation (quarkus-langchain4j 1.9.2) — citations in
ADR 0008. The `dummy` fallback above sidesteps the one unverified state
(property explicitly *empty*); the remaining implementation-time check is
confirmatory: prod-profile boot without `NVIDIA_API_KEY` must not fail
startup (lazy client). Finding recorded in ADR 0008 either way.

New dependency: `io.quarkiverse.langchain4j:quarkus-langchain4j-openai`
(implementation), `org.wiremock:wiremock` (testImplementation).

## 4. Boundaries (ADR 0002 — unchanged rules, new edges)

| Edge | Status |
|------|--------|
| `runtime → memory.preference` | allowed (`memory.*`) — resolution reads preference |
| `adapter.telegram → memory.preference` | explicitly allowed by the boundary table — `/provider` writes |
| `adapter.telegram → runtime` queries | allowed — `/status`, validation names |
| `provider.nim → langchain4j` | allowed (`provider.<name>`) |
| `adapter.* → provider.*`, `runtime → provider.<name>` | still forbidden; resolution goes through the SPI's `name()` |

Three naming domains exist and must not be conflated (ADR 0008 paragraph):
quark gateway ids (`gemini`, `nim`), quarkus-langchain4j provider ids
(`ai-gemini`, `openai`), langchain4j named-model name (`nim` in
`quarkus.langchain4j.openai.nim.*`).

## 5. Test plan

1. `InMemoryProviderPreferenceStoreTest` (plain JUnit): empty get, set/get,
   overwrite, session isolation.
2. `NimModelGatewayTest` (plain JUnit, fake `StreamingChatModel`): token
   order + completion, failure propagation, role mapping, `name()`.
3. `NimModelGatewayWireMockTest` (`@QuarkusTest` + WireMock via
   `QuarkusTestResourceLifecycleManager(restrictToAnnotatedClass = true)`):
   stubs `POST /v1/chat/completions` SSE; overrides
   `quarkus.langchain4j.openai.nim.base-url`; asserts
   `Authorization: Bearer test-key`, streamed token order, model-name in the
   request body. Proves the wire path a fake model cannot: named-model
   config, base-url override, SSE parsing.
4. `AgentRuntimeTest` additions (fakes gain `name()`): precedence explicit >
   preference > default; unknown dynamic name → `TurnStarted` → `TurnFailed`,
   zero persistence, zero gateway invocations; `ModelInvoked.provider`
   carries the resolved name; `resolveProvider`/`messageCount`/`providers`.
5. Startup validation: plain JUnit on the package-private `validate()`
   logic with fake gateways (duplicate names; default not present); the
   `StartupEvent` observer wiring is one line, proven by the rest of the
   suite booting. (`QuarkusUnitTest` rejected — extension-development
   machinery and a new internal dependency for what a unit test covers.)
6. `TelegramCommandsTest` additions: `/provider`, `/provider nim`,
   `/provider@bot nim`, `/provider NIM`, `/provider nim extra`, `/status`,
   existing cases untouched (return-type migration is mechanical).
7. Adapter dispatch tests: all six reply rows of §3.6 (including the
   `/provider gemini` no-hint case), exact strings — deterministic via
   sorted `providers()` and the settable start-instant seam.
8. **Composed-path test:** `@QuarkusTest` with two recording
   `StreamingChatModel`s — default bean via
   `QuarkusMock.installMockForType(mock, StreamingChatModel.class)`, nim
   bean via the **qualifier overload**
   `installMockForType(mock, StreamingChatModel.class, new ModelName.Literal("nim"))`
   (the plain two-arg overload targets only the default bean; overload
   verified against quarkus-junit 3.35.4): `dispatch("/provider nim")` then
   a chat turn → the nim-side model received the prompt. Two green unit
   tests on either side of a seam don't prove the seam (§8 altitude lesson).
9. Migration list (behavior unchanged, mechanically touched):
   `AgentRuntime` constructor, `AgentRuntimeTest.FakeGateway` (+`name()`),
   `RuntimeWiringTest` (`@Any Instance<ModelGateway>`, asserts both names).
   §8 memory backstops (`TelegramConversationMemoryTest`,
   `TelegramStreamingMemoryTest`) textually unchanged, re-verified green —
   their `QuarkusMock` targets the default model bean, which stays Gemini's.

**Sequencing constraint (gate-critical):** a second unqualified
`ModelGateway` bean makes every unqualified injection ambiguous and fails
deployment for the entire suite. Order: (1) `name()` on SPI + Gemini + fakes;
(2) runtime switches to `Instance<ModelGateway>` + resolution with one
gateway, suite green; (3) only then `NimModelGateway` + config.
**Config landing order (same hazard, config-shaped):**
`quark.provider.default=gemini` lands with step (2) — the constructor
property has no `defaultValue`, so step (2) fails boot without it. The
`quarkus-langchain4j-openai` dependency, the `nim` named-model block, and
`quarkus.langchain4j.chat-model.provider=ai-gemini` land **atomically with
step (3)** — the dependency without the disambiguation line leaves the
default model bean ambiguous and fails deployment.

**No live smoke from the authoring environment** (NIM endpoint egress-
blocked): live verification = user-run Telegram smoke post-merge; recorded
honestly in `docs/progress.md` per §8.

## 6. ADR 0008 (to be authored with the implementation)

"Named providers and per-session preference resolution": `name()` as the
stable id; precedence chain; static-fail-startup vs dynamic-fail-turn;
lazy unconfigured-provider failure (decision + rationale); `/reset` keeps
preference; the three naming domains; adapter-side preference writes with
revisit trigger ("second adapter duplicates validation → lift `setProvider`
to the runtime"); doc-check citations (embedded config docs, 1.9.2);
`ModelInvoked.provider` and why enriching ≠ breaking; the `timeout=60s`
override rationale.
