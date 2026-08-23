# Plan 5 WIP archive — NIM provider + provider preference

**Status:** paused experiment, NOT production-ready. This branch is a snapshot of the
unfinished Plan 5 work as it stood on 2026-08-23, preserved before removal from the
active baseline (`main`). Do not merge as-is.

## Why paused

Plan 5 (NIM provider + `/provider` + `/status`) was sequenced by ADR 0003 before the
framework-independent runtime direction existed ([ADR 0008](../../adr/0008-framework-independent-runtime-and-kotlin-migration.md)).
A second provider is more valuable *after* the provider boundary is framework-neutral
(ADR 0008 Migration 3 onward). The NIM provider itself is not rejected — the sequencing
was.

## Contents

- `src/main/java/com/quark/provider/nim/NimModelGateway.java` — NIM gateway over the
  OpenAI-compatible endpoint via `quarkus-langchain4j-openai`, mirrors Gemini semantics;
- `src/test/java/com/quark/provider/nim/NimModelGatewayTest.java` + build/config changes
  (`quarkus-langchain4j-openai` dep, `%test` keys, provider disambiguation config);
- `AgentRuntime` provider-resolution chain: session preference > `TurnRequest.provider()`
  > `quark.provider.default`; unknown provider => `TurnStarted`+`TurnFailed`, no fallback;
  CDI `@Any Instance<ModelGateway>` + `@Named` selection; `@ConfigProperty` default;
- `@Named("gemini")` / `@ModelName("gemini")` on `GeminiModelGateway`; mock installs in two
  Telegram tests updated to `ModelName.Literal.of("gemini")`;
- new `AgentRuntimeTest` coverage: 6 provider-resolution tests (preference priority, request
  override, default, unknown provider, reset-does-not-clear-preference) + a hand-rolled
  fake CDI `Instance` + `FakePreferenceStore` (documents the SPI shape, see below);
- `RuntimeWiringTest`: both gateways resolvable by name;
- `io/quarkiverse/langchain4j/ModelName.java` — vendored copy of the quarkiverse annotation,
  accidentally placed at repo root OUTSIDE any source root (never compiled); reference
  material only.

## KNOWN BROKEN — this snapshot does not compile

Two referenced classes were never written (or never saved) before the work was paused:

1. `com.quark.memory.preference.ProviderPreferenceStore` — SPI used by `AgentRuntime`:
   `Optional<ModelPreference> get(String sessionId)`, `void set(String sessionId,
   ModelPreference preference)`, `void clear(String sessionId)` (shape per
   `FakePreferenceStore` in `AgentRuntimeTest`);
2. `com.quark.memory.preference.ModelPreference` — value type with
   `ModelPreference.ofProvider(String)` and a `provider()` accessor (per test usage).

Reconstruct those two to make the branch compile; then finish the never-started
`/provider` + `/status` Telegram commands (Plan 5 scope) — none of that exists here.

## Config warning for a future revisit

The WIP `application.properties` downgraded the Gemini model id from
`gemini-3.1-flash-lite` (current `main`) to `gemini-2.0-flash-lite` while reshaping keys
for multi-provider disambiguation. `docs/progress.md` records that `gemini-2.0-flash-lite`
previously hit a live 404 (the Plan-1 model-id trap). Verify the model id against the live
API before reusing this config.

## Resume checklist (when the neutral provider boundary exists)

1. Re-read ADR 0008; re-evaluate whether provider selection belongs in the runtime core or
   becomes host/routing concern under the neutral contracts.
2. Reconstruct the two missing `memory.preference` classes (or redesign them away).
3. Port the gateway behind the neutral (Kotlin/Flow) provider SPI — do not carry `Multi<String>`
   forward mechanically.
