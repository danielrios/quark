# Plan 1 — Telegram + Gemini Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A running Quarkus app where messaging a Telegram bot gets a one-shot Gemini reply — text in, text out, nothing else.

**Architecture:** A virtual-thread long-poll loop reads Telegram `getUpdates`, hands each text message directly to a LangChain4j `@RegisterAiService` (Gemini), and posts the answer back via `sendMessage`. No memory, no streaming, no commands, no REST, no runtime abstractions. The Telegram client is a hand-rolled Quarkus REST Client (two endpoints) — chosen over a framework per `MANIFESTO.md` ("explicit > magical", "easy to rewrite") and because it yields pure, deterministic parsing logic we can unit-test without the network.

**Tech Stack:** Java 25, Quarkus 3.35.4 (Gradle Kotlin DSL), `quarkus-langchain4j-ai-gemini`, `quarkus-rest-client-jackson`, JUnit 5.

---

## Scope & decisions (read before starting)

This plan implements **only** ADR 0003 Plan 1: *"Telegram polling + Gemini, single message in / single message out. None. Direct `@RegisterAiService` injection."*

**Explicitly OUT of scope** (each deferred to a later plan — do not add them):

| Deferred | Owner plan |
|----------|------------|
| In-memory chat history, `/reset` | Plan 2 |
| `/start`, `/status` commands | Plan 2 |
| Telegram streaming / throttled edits | Plan 3 |
| `ChatService`, `ChatMemory`, a dispatcher class | Plan 2 / 4 |
| `AgentRuntime`, `AgentEvent`, `ModelGateway` | Plan 4 |
| REST `POST /chat`, SSE | Plan 6 |
| ArchUnit, Micrometer | Plan 7 |

The Telegram runner injects `Assistant` **directly**. No orchestration layer. The spec's full `chat/` + `telegram/` package layout and the command table are the *end-of-MVP* shape (reached at Plan 3+), not Plan 1.

**Decision record:** the hand-rolled-Telegram-client choice and this scope are captured here and must be restated in the PR body (CLAUDE.md §6 — "architecture decisions go somewhere"). No ADR needed; this plan file + PR body is the record.

**Harness contract (CLAUDE.md):**
- The test gate is the **quarkus-agent MCP**, not `./gradlew test`. Run it with `/baseline-test` (wraps `quarkus_callTool` → `toolName: "devui-testing_runTests"`). A task is done only at **zero failures / zero errors**.
- Dev mode runs **only** through the MCP: `quarkus_start` / `quarkus_status` / `quarkus_logs`. Never `./gradlew quarkusDev` (hard-blocked).
- WIP = 1: finish a task before starting the next. Commit at the end of every task.
- `git add/commit` will prompt for approval — that is expected.

---

## File Structure

**Create:**
- `src/main/java/com/quark/chat/Assistant.java` — `@RegisterAiService` Gemini interface; one stateless method `String chat(String)`.
- `src/main/java/com/quark/telegram/TelegramMessages.java` — Telegram Bot API DTO records (subset) **and** the pure helpers `extractText` / `nextOffset`. The only deterministically testable unit.
- `src/main/java/com/quark/telegram/TelegramApi.java` — `@RegisterRestClient` interface: `getUpdates` + `sendMessage`.
- `src/main/java/com/quark/telegram/TelegramBotRunner.java` — startup observer, virtual-thread poll loop, per-message handler.
- `src/test/java/com/quark/telegram/TelegramMessagesTest.java` — plain JUnit 5 tests for the pure helpers (no `@QuarkusTest`, no network).

**Modify:**
- `build.gradle.kts` — add the two extensions.
- `src/main/resources/application.properties` — Gemini + Telegram + REST-client config, with `%test` overrides.
- `README.md` — how to run the bot and what tests need (nothing).
- `docs/progress.md` — point the current-task pointer at this plan, then log completion.

**Untouched (still part of the gate):** `GreetingResource.java` / `GreetingResourceTest.java`. The latter boots the full CDI context, so it is our free integration check that the new beans + Gemini extension wire up.

---

### Task 0: Baseline gate + branch

**Files:** none (environment + git only).

- [ ] **Step 1: Create the feature branch**

We are on `main`; branch first.

```bash
git checkout -b claude/plan-1-telegram-gemini-skeleton
```

- [ ] **Step 2: Start dev mode through the MCP**

Use the quarkus-agent MCP `quarkus_start` with `projectDir: "/home/rios/projects/quark"`.
Then `quarkus_status` — expected: `running` with a port.
**Do not** run `./gradlew quarkusDev` (hard-blocked by the harness).

If dev mode will not start (e.g. Java 25 / MCP not wired — see `docs/progress.md` 2026-05-26 note), **stop here**: the whole TDD loop depends on the gate running. Record the blocker in `docs/progress.md` and ask the human. Do not proceed to Task 1.

- [ ] **Step 3: Record the pre-state**

Run: `/baseline-test`
Expected: `GreetingResourceTest` runs, **zero failures / zero errors**. This is the recorded pre-state (CLAUDE.md §3).

- [ ] **Step 4: Point the progress ledger at this plan**

Edit `docs/progress.md` → replace the "Current Task" bullet with:

```markdown
## Current Task
- Plan 1 — Telegram + Gemini walking skeleton — executing `docs/superpowers/plans/2026-05-28-plan-1-telegram-gemini-skeleton.md`.
```

- [ ] **Step 5: Commit**

```bash
git add docs/progress.md docs/superpowers/plans/2026-05-28-plan-1-telegram-gemini-skeleton.md
git commit -m "docs: open Plan 1 (telegram + gemini skeleton)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 1: Add the Gemini + REST-client extensions and confirm they resolve

This task front-loads the one set of facts we cannot verify by reading: the exact Gemini extension coordinate, config keys, AI-service patterns, and whether the extension demands a key at startup. Adding the dependency makes `quarkus_skills` able to answer all of them (it reads the built extension JARs).

**Files:**
- Modify: `build.gradle.kts:16-24`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the dependencies**

In `build.gradle.kts`, inside the `dependencies { }` block, add the Gemini extension and the REST client (the langchain4j BOM is already enforced, so no version is needed on the Gemini line):

```kotlin
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-ai-gemini")
    implementation("io.quarkus:quarkus-rest-client-jackson")
```

- [ ] **Step 2: Add Gemini config + a test/dev-safe key**

Append to `src/main/resources/application.properties`:

```properties
# --- Gemini (Plan 1) ---
quarkus.langchain4j.ai.gemini.api-key=${GEMINI_API_KEY:}
quarkus.langchain4j.ai.gemini.chat-model.model-name=gemini-2.0-flash

# Tests must boot without real secrets, so the AI-service bean has a key to construct with.
%test.quarkus.langchain4j.ai.gemini.api-key=test-key
```

- [ ] **Step 3: Confirm the real patterns from the extension JAR**

Now that the dependency is on the classpath, call the quarkus-agent MCP `quarkus_skills` with `projectDir: "/home/rios/projects/quark"` and `query: "gemini"` (then again with `query: "rest-client"`). Confirm and, if they differ, correct in this plan and in `application.properties`:
1. the exact config key for the API key and model name,
2. the `@RegisterAiService` import + `@SystemMessage` usage (Task 3),
3. **whether an AI service with no `@MemoryId` is stateless** — Plan 1 requires each message independent (no cross-user memory). If the extension defaults to shared memory, note the config to disable it; apply it in Task 3.
4. whether the extension fails at **startup** vs **first call** when the key is missing.

- [ ] **Step 4: Run the gate — context still boots**

Dev mode hot-reloads on the dependency change (give it a moment; check `quarkus_status` is `running`, use `quarkus_logs` if it crashed).
Run: `/baseline-test`
Expected: `GreetingResourceTest` **passes** — proving the app boots with the Gemini extension present and the `%test` key. Zero failures / zero errors.

If startup fails complaining about a missing/blank key in dev mode (not test), change the main default to a placeholder so dev mode boots and auth fails only at call time:
`quarkus.langchain4j.ai.gemini.api-key=${GEMINI_API_KEY:MISSING}`

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/main/resources/application.properties
git commit -m "feat: add gemini and rest-client extensions

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Pure Telegram message logic (TDD)

The deterministic core: turn a Telegram `Update` into either an `(chatId, text)` pair or "skip", and compute the next long-poll offset. Pure functions, no Quarkus, no network — the real test surface.

**Files:**
- Test: `src/test/java/com/quark/telegram/TelegramMessagesTest.java`
- Create: `src/main/java/com/quark/telegram/TelegramMessages.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/quark/telegram/TelegramMessagesTest.java`:

```java
package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.telegram.TelegramMessages.Chat;
import com.quark.telegram.TelegramMessages.IncomingText;
import com.quark.telegram.TelegramMessages.Message;
import com.quark.telegram.TelegramMessages.TelegramUpdate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TelegramMessagesTest {

    @Test
    void extractsChatIdAndTextFromTextMessage() {
        var update = new TelegramUpdate(42, new Message(new Chat(7), "hello"));
        Optional<IncomingText> result = TelegramMessages.extractText(update);
        assertTrue(result.isPresent());
        assertEquals(7, result.get().chatId());
        assertEquals("hello", result.get().text());
    }

    @Test
    void ignoresUpdateWithoutMessage() {
        var update = new TelegramUpdate(42, null);
        assertTrue(TelegramMessages.extractText(update).isEmpty());
    }

    @Test
    void ignoresMessageWithoutText() {
        var update = new TelegramUpdate(42, new Message(new Chat(7), null));
        assertTrue(TelegramMessages.extractText(update).isEmpty());
    }

    @Test
    void ignoresBlankText() {
        var update = new TelegramUpdate(42, new Message(new Chat(7), "   "));
        assertTrue(TelegramMessages.extractText(update).isEmpty());
    }

    @Test
    void nextOffsetIsHighestUpdateIdPlusOne() {
        var updates = List.of(
                new TelegramUpdate(10, null),
                new TelegramUpdate(12, null),
                new TelegramUpdate(11, null));
        assertEquals(13, TelegramMessages.nextOffset(0, updates));
    }

    @Test
    void nextOffsetUnchangedForEmptyBatch() {
        assertEquals(5, TelegramMessages.nextOffset(5, List.of()));
    }
}
```

- [ ] **Step 2: Run the gate to verify it fails**

Run: `/baseline-test`
Expected: **FAIL** — compilation error, `cannot find symbol: class TelegramMessages` (the class does not exist yet). This is the red state.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/java/com/quark/telegram/TelegramMessages.java`:

```java
package com.quark.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/** Telegram Bot API payloads (subset) and pure helpers over them. */
public final class TelegramMessages {

    private TelegramMessages() {}

    public record GetUpdatesResponse(boolean ok, List<TelegramUpdate> result) {}

    public record TelegramUpdate(@JsonProperty("update_id") long updateId, Message message) {}

    public record Message(Chat chat, String text) {}

    public record Chat(long id) {}

    public record SendMessage(@JsonProperty("chat_id") long chatId, String text) {}

    public record IncomingText(long chatId, String text) {}

    /** Present only for non-blank text messages that carry a chat id. */
    public static Optional<IncomingText> extractText(TelegramUpdate update) {
        Message m = update.message();
        if (m == null || m.chat() == null || m.text() == null || m.text().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new IncomingText(m.chat().id(), m.text()));
    }

    /** Next long-poll offset: highest update_id + 1, or unchanged when the batch is empty. */
    public static long nextOffset(long current, List<TelegramUpdate> updates) {
        long max = current - 1;
        for (TelegramUpdate u : updates) {
            max = Math.max(max, u.updateId());
        }
        return max + 1;
    }
}
```

- [ ] **Step 4: Run the gate to verify it passes**

Run: `/baseline-test`
Expected: `TelegramMessagesTest` (6 tests) and `GreetingResourceTest` all **pass**. Zero failures / zero errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/quark/telegram/TelegramMessages.java src/test/java/com/quark/telegram/TelegramMessagesTest.java
git commit -m "feat: parse telegram updates and compute poll offset

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Gemini AI service (`Assistant`)

The single Gemini seam — a stateless `@RegisterAiService`. There is no deterministic automated test for a live LLM (network + secret); the automated proof is that `GreetingResourceTest` still boots the context with this bean present. The live behaviour is verified manually in Task 5.

**Files:**
- Create: `src/main/java/com/quark/chat/Assistant.java`

- [ ] **Step 1: Create the AI service**

Create `src/main/java/com/quark/chat/Assistant.java` (adjust imports/annotations to match what `quarkus_skills` reported in Task 1, Step 3):

```java
package com.quark.chat;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * One-shot Gemini chat. Stateless by design for Plan 1 — no @MemoryId, so each
 * call is independent (no cross-message, no cross-user memory). Memory arrives in Plan 2.
 */
@RegisterAiService
public interface Assistant {

    @SystemMessage("You are quark, a concise and helpful assistant. Answer in plain text.")
    String chat(String userMessage);
}
```

- [ ] **Step 2: Enforce statelessness if the extension defaults to shared memory**

Using the finding from Task 1, Step 3: if an AI service without `@MemoryId` retains a shared `ChatMemory`, Plan 1's "single message in / out" contract is violated (every user would share one context). Apply the extension's documented config to disable memory for this service, in `application.properties`. If the extension is already stateless without `@MemoryId`, do nothing and note that in the commit body.

- [ ] **Step 3: Run the gate — context boots with the bean**

Dev mode hot-reloads. Run: `/baseline-test`
Expected: `GreetingResourceTest` + `TelegramMessagesTest` all **pass** — proving the `@RegisterAiService` bean constructs (with the `%test` key) and CDI wiring is valid. Zero failures / zero errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/quark/chat/Assistant.java src/main/resources/application.properties
git commit -m "feat: add stateless gemini assistant ai service

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Telegram transport — REST client + resilient poll loop

Wire the skeleton end-to-end: a two-method REST client and a virtual-thread poller that turns text messages into Gemini replies. No new automated test (the path is network-bound); the runner is config-gated **off** in tests, so `GreetingResourceTest` keeps the gate green, and the live path is smoke-tested in Task 5.

**Files:**
- Create: `src/main/java/com/quark/telegram/TelegramApi.java`
- Create: `src/main/java/com/quark/telegram/TelegramBotRunner.java`
- Modify: `src/main/resources/application.properties`
- Modify: `README.md`

- [ ] **Step 1: Create the REST client**

Create `src/main/java/com/quark/telegram/TelegramApi.java`:

```java
package com.quark.telegram;

import com.quark.telegram.TelegramMessages.GetUpdatesResponse;
import com.quark.telegram.TelegramMessages.SendMessage;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Telegram Bot API (subset). Base URL carries the bot token; see application.properties. */
@RegisterRestClient(configKey = "telegram")
public interface TelegramApi {

    @GET
    @Path("/getUpdates")
    GetUpdatesResponse getUpdates(@QueryParam("offset") long offset, @QueryParam("timeout") int timeout);

    @POST
    @Path("/sendMessage")
    void sendMessage(SendMessage message);
}
```

- [ ] **Step 2: Create the poll loop runner**

Create `src/main/java/com/quark/telegram/TelegramBotRunner.java`:

```java
package com.quark.telegram;

import com.quark.chat.Assistant;
import com.quark.telegram.TelegramMessages.GetUpdatesResponse;
import com.quark.telegram.TelegramMessages.IncomingText;
import com.quark.telegram.TelegramMessages.SendMessage;
import com.quark.telegram.TelegramMessages.TelegramUpdate;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Long-polls Telegram and answers each text message with a one-shot Gemini reply. */
@ApplicationScoped
public class TelegramBotRunner {

    @Inject
    @RestClient
    TelegramApi api;

    @Inject
    Assistant assistant;

    @ConfigProperty(name = "quark.telegram.enabled")
    boolean enabled;

    @ConfigProperty(name = "quark.telegram.bot-token")
    String botToken;

    @ConfigProperty(name = "quark.telegram.poll-timeout-seconds", defaultValue = "30")
    int pollTimeoutSeconds;

    private volatile boolean running = true;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            Log.info("Telegram disabled (quark.telegram.enabled=false); poller not started");
            return;
        }
        if (botToken == null || botToken.isBlank()) {
            Log.warn("Telegram enabled but no bot token set; poller not started");
            return;
        }
        // Spawn on a virtual thread and return immediately — never block startup.
        Thread.ofVirtual().name("telegram-poll").start(this::pollLoop);
        Log.info("Telegram poller started");
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
    }

    void pollLoop() {
        long offset = 0;
        while (running) {
            try {
                GetUpdatesResponse resp = api.getUpdates(offset, pollTimeoutSeconds);
                List<TelegramUpdate> updates =
                        (resp == null || resp.result() == null) ? List.of() : resp.result();
                for (TelegramUpdate u : updates) {
                    handle(u);
                }
                offset = TelegramMessages.nextOffset(offset, updates);
            } catch (Exception e) {
                // One bad poll must not kill the loop. Back off briefly to avoid a hot loop.
                Log.error("Telegram poll iteration failed", e);
                sleepQuietly(1000);
            }
        }
    }

    /** Never throws — a poison-pill update must not stall offset advancement. */
    private void handle(TelegramUpdate update) {
        try {
            IncomingText incoming = TelegramMessages.extractText(update).orElse(null);
            if (incoming == null) {
                return;
            }
            String reply;
            try {
                reply = assistant.chat(incoming.text());
            } catch (Exception e) {
                Log.error("Gemini call failed", e);
                reply = "Something went wrong.";
            }
            api.sendMessage(new SendMessage(incoming.chatId(), reply));
        } catch (Exception e) {
            Log.error("Failed to handle update " + update.updateId(), e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 3: Add Telegram + REST-client config**

Append to `src/main/resources/application.properties`:

```properties
# --- Telegram (Plan 1) ---
quark.telegram.enabled=${QUARK_TELEGRAM_ENABLED:true}
quark.telegram.bot-token=${TELEGRAM_BOT_TOKEN:}
quark.telegram.poll-timeout-seconds=30

# Base URL embeds the bot token. Read timeout must exceed the long-poll timeout above.
quarkus.rest-client.telegram.url=https://api.telegram.org/bot${quark.telegram.bot-token}
quarkus.rest-client.telegram.read-timeout=40000

# Tests never poll Telegram.
%test.quark.telegram.enabled=false
```

- [ ] **Step 4: Update the README run instructions**

In `README.md`, add a "Run the bot" section documenting:
- export `GEMINI_API_KEY` and `TELEGRAM_BOT_TOKEN` (from BotFather),
- start dev mode via the quarkus-agent MCP (`quarkus_start`) — never `./gradlew quarkusDev`,
- message the bot in Telegram to get a Gemini reply,
- tests require **no** secrets (`%test` disables Telegram and uses a dummy Gemini key).

- [ ] **Step 5: Run the gate — runner is off in tests, context still green**

Dev mode hot-reloads. Run: `/baseline-test`
Expected: `GreetingResourceTest` + `TelegramMessagesTest` all **pass**. The runner's `onStart` returns early under the `%test` profile (`quark.telegram.enabled=false`), so no polling happens during tests. Zero failures / zero errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/quark/telegram/TelegramApi.java src/main/java/com/quark/telegram/TelegramBotRunner.java src/main/resources/application.properties README.md
git commit -m "feat: telegram long-poll loop answering with gemini

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Manual end-to-end smoke + close out the plan

The walking skeleton's actual point: prove a real Telegram message gets a real Gemini reply. This is a **manual** step (real token + key, real network) and is intentionally outside the automated gate (spec §12 — integration is env-gated/manual).

**Files:**
- Modify: `docs/progress.md`

- [ ] **Step 1: Run the live path**

In the session shell, with a real bot token and key (use the `!` prefix so output lands in the session, e.g. `! export GEMINI_API_KEY=...`):
- export `GEMINI_API_KEY` and `TELEGRAM_BOT_TOKEN`,
- restart dev mode via the MCP (`quarkus_stop` then `quarkus_start`) so it picks up the env,
- confirm `quarkus_logs` shows `Telegram poller started`,
- send the bot a message from Telegram (e.g. "say hi in five words").

Expected: a Gemini reply arrives in the chat; `quarkus_logs` shows no loop errors. If Gemini fails, you get "Something went wrong." and the loop keeps running — note the log error and fix config (key/model) before declaring done.

- [ ] **Step 2: Final gate**

Run: `/baseline-test`
Expected: zero failures / zero errors (the automated suite is unchanged from Task 4).

- [ ] **Step 3: Record completion**

Run: `/progress Plan 1 complete — Telegram long-poll + Gemini one-shot reply verified end-to-end.`

- [ ] **Step 4: Commit**

```bash
git add docs/progress.md
git commit -m "docs: record Plan 1 completion

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 5: Finish the branch**

Use superpowers:finishing-a-development-branch to choose how to integrate (PR vs merge). The PR body must restate the two decisions from the "Scope & decisions" section: hand-rolled Telegram client (per MANIFESTO) and strict ADR 0003 Plan 1 scope.

---

## Self-Review

**Spec coverage (ADR 0003 Plan 1 = "Telegram polling + Gemini, single message in / single message out. None. Direct `@RegisterAiService` injection"):**
- Telegram long polling → Task 4 (`TelegramBotRunner` + `getUpdates`). ✓
- Gemini → Task 1 (extension) + Task 3 (`Assistant`). ✓
- Single message in / out, no memory/streaming → stateless `Assistant` (Task 3, Step 2 enforces it); no buffering. ✓
- Direct `@RegisterAiService` injection → runner injects `Assistant`, no orchestration layer. ✓
- Friendly error on provider failure (spec §10 minimal form) → `handle()` catch → "Something went wrong." ✓
- Harness alignment → MCP gate every task, MCP-only dev mode, branch-first, per-task commits, `/progress`, no `quarkusDev`. ✓
- Out-of-scope items named and deferred → Scope table. ✓

**Placeholder scan:** No "TBD"/"handle edge cases"/"similar to Task N". The two genuinely unverifiable facts (Gemini config keys, default-memory behaviour) are not placeholders — they are concrete best-known values plus an explicit confirm-and-correct step (Task 1 Step 3 / Task 3 Step 2), which is the correct way to handle facts only the built JAR can confirm.

**Type consistency across tasks:** `TelegramMessages.{GetUpdatesResponse, TelegramUpdate, Message, Chat, SendMessage, IncomingText}` defined in Task 2 and consumed unchanged in Tasks 2/4. `TelegramMessages.extractText(TelegramUpdate) : Optional<IncomingText>` and `nextOffset(long, List<TelegramUpdate>) : long` — same signatures in test (Task 2), impl (Task 2), runner (Task 4). `Assistant.chat(String) : String` — Task 3 def, Task 4 call. `TelegramApi.{getUpdates(long,int), sendMessage(SendMessage)}` — Task 4 def + call. Config keys (`quark.telegram.enabled/bot-token/poll-timeout-seconds`, `quarkus.rest-client.telegram.url/read-timeout`, `quarkus.langchain4j.ai.gemini.api-key/chat-model.model-name`) consistent between `application.properties` and `@ConfigProperty`/`@RegisterRestClient(configKey="telegram")`. ✓
