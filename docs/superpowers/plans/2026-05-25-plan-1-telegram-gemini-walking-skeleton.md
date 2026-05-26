# Plan 1 — Telegram + Gemini Walking Skeleton

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A minimal Quarkus service that runs as a Telegram bot: long-polls Telegram for messages, hands each message to a Google Gemini model, and sends the model's reply back. No memory, no streaming, no abstractions, no second provider.

**Architecture:** Direct, flat, intentionally minimal. A `@RegisterAiService` interface (`Assistant`) for the model call, a `Dispatcher` that decides between `/start` and "delegate to model", a Quarkus REST client (`TelegramClient`) for Telegram's HTTP API, and a `QuarkBot` bean that runs the polling loop on a virtual thread. The `AgentRuntime`, `AgentEvent` stream, `ModelGateway`, `ChatMemoryStore`, and `ProviderPreferenceStore` abstractions from the slice 1 spec are **not** introduced here — they will be extracted as a refactor in plan 4 once there is enough actual code to justify the abstractions.

**Tech Stack:** Quarkus 3.35.4, Java 25, langchain4j (via `quarkus-langchain4j-ai-gemini` Quarkiverse extension), Quarkus REST client + Jackson, virtual-thread polling, JUnit 5, Mockito (via `quarkus-junit5-mockito`).

**Spec reference:** [`docs/superpowers/specs/2026-05-25-agent-runtime-slice-1-design.md`](../specs/2026-05-25-agent-runtime-slice-1-design.md). This plan is intentionally smaller than the slice 1 design. Subsequent plans grow the codebase toward the spec:

| Plan | Adds |
|---|---|
| 1 (this one) | Telegram polling + Gemini, no memory, no streaming |
| 2 | In-process working memory; `/reset` command |
| 3 | Streaming via Telegram message edits (throttled + chunked) |
| 4 | Extract `AgentRuntime` + `AgentEvent` + `ModelGateway` abstractions |
| 5 | NIM provider + `ProviderPreferenceStore` + `/provider`, `/status` commands |
| 6 | REST + SSE adapter |
| 7 | ArchUnit boundaries + Micrometer observability |

---

## File structure

### New files (5 source + 1 test)

| File | Responsibility |
|---|---|
| `src/main/java/com/quark/Assistant.java` | `@RegisterAiService` interface — single method that takes a user message and returns the model's reply as a `String`. Quarkus wires it to the configured Gemini chat model. |
| `src/main/java/com/quark/Dispatcher.java` | Plain CDI bean. Method `reply(String userText)`. If the text starts with `/start`, returns a hardcoded welcome string. Otherwise calls `Assistant.chat(userText)`. **All command/dispatch logic lives here; it is the only file with non-trivial behaviour, and it is the only unit-tested file.** |
| `src/main/java/com/quark/telegram/TelegramApi.java` | One Java file holding three nested `record` types — `Update`, `Message`, `Chat` — and one top-level `record GetUpdatesResponse`. Pure Jackson DTOs for the subset of the Telegram Bot API we use. |
| `src/main/java/com/quark/telegram/TelegramClient.java` | `@RegisterRestClient` interface. Two methods: `getUpdates(offset, timeout)` and `sendMessage(chatId, text)`. Base URL (including the bot token) comes from config so no path-template token is needed. |
| `src/main/java/com/quark/telegram/QuarkBot.java` | `@ApplicationScoped` bean. Observes `StartupEvent`; if `quark.telegram.enabled=true`, spawns a virtual thread that runs the polling loop forever until `ShutdownEvent`. Each loop iteration: `getUpdates` → for each update, `Dispatcher.reply` → `sendMessage`. **No business logic; pure coordination.** |
| `src/test/java/com/quark/DispatcherTest.java` | Plain JUnit (no `@QuarkusTest`). Constructs `Dispatcher` with a lambda fake for `Assistant`. Two tests: `/start` returns welcome without calling the model; any other text returns the model's reply verbatim. |

### Modified files

| File | Change |
|---|---|
| `build.gradle.kts` | Add three runtime dependencies (`quarkus-langchain4j-ai-gemini`, `quarkus-rest-client-jackson`, `quarkus-scheduler` not needed — using virtual thread directly) and one test dependency (`quarkus-junit5-mockito` — used in plan 1 only for the `quarkus-junit5-mockito` framework integration so future tests can use it; the dispatcher test itself uses a hand-rolled lambda fake). |
| `src/main/resources/application.properties` | Add Gemini config, REST client base URL (with bot token interpolated from env), Telegram enable flag, and poll timeout. |

### Untouched files

The existing `GreetingResource` + `GreetingResourceTest` + `GreetingResourceIT` stay as-is. They serve as a boot probe (`GET /hello`) and prove `@QuarkusTest` works. Removing them is a tiny refactor for a future plan if desired.

---

## Task 1: Add dependencies and configuration

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add runtime dependencies to `build.gradle.kts`**

Open `build.gradle.kts`. Locate the existing `dependencies { ... }` block:

```kotlin
dependencies {
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-core")
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:quarkus-langchain4j-bom:${quarkusPlatformVersion}"))
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
}
```

Replace it with:

```kotlin
dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:quarkus-langchain4j-bom:${quarkusPlatformVersion}"))
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-core")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-ai-gemini")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
}
```

Notes:
- `quarkus-junit` was replaced with the canonical artifact name `quarkus-junit5` (same artifact, alias). If the existing line works on your Quarkus version, leave it. The build will fail loudly if the coordinate is wrong.
- `quarkus-junit5-mockito` is added now to make Mockito available for future tests; this plan does not actually use Mockito.

- [ ] **Step 2: Update `application.properties`**

The file is currently empty (or a single blank line). Replace its contents entirely with:

```properties
# Application
quarkus.application.name=quark

# Gemini chat model (Quarkiverse langchain4j extension)
quarkus.langchain4j.ai.gemini.api-key=${GEMINI_API_KEY:}
quarkus.langchain4j.ai.gemini.chat-model.model-name=${GEMINI_MODEL:gemini-1.5-flash}

# Telegram REST client. The bot token is interpolated into the base URL at
# runtime so we never need a JAX-RS path parameter for it. If TELEGRAM_BOT_TOKEN
# is unset the URL becomes "https://api.telegram.org/bot" which is invalid;
# QuarkBot does not start polling unless quark.telegram.enabled=true, so the
# invalid URL is never called.
quarkus.rest-client.telegram.url=https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN:}

# Telegram bot wiring
quark.telegram.enabled=${QUARK_TELEGRAM_ENABLED:false}
quark.telegram.poll-timeout-seconds=30
```

- [ ] **Step 3: Verify the build still passes**

Run: `./gradlew compileJava compileTestJava`
Expected: `BUILD SUCCESSFUL`. No compile errors. (No application code references the new dependencies yet, so this only proves the Gradle resolution works.)

If you see "Could not find io.quarkiverse.langchain4j:quarkus-langchain4j-ai-gemini" — the extension version is governed by `quarkus-langchain4j-bom`. Check the BOM's effective version contains an `ai-gemini` artifact. If it doesn't, the extension coordinate may have changed in this Quarkus version; consult the Quarkiverse langchain4j extension list and adjust accordingly. (This is one of the "items to verify during implementation" called out in §8 of the spec.)

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/resources/application.properties
git commit -m "chore: add gemini + telegram rest client deps and config"
```

---

## Task 2: `Assistant` interface

**Files:**
- Create: `src/main/java/com/quark/Assistant.java`

- [ ] **Step 1: Create the file**

`src/main/java/com/quark/Assistant.java`:

```java
package com.quark;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface Assistant {

    String chat(@UserMessage String userMessage);
}
```

That's the whole file. The `@RegisterAiService` annotation tells the Quarkus langchain4j extension to generate a CDI bean implementing this interface, wired to the configured chat model (Gemini, per `application.properties`). `@UserMessage` marks the parameter as the user's message.

The interface is intentionally a single-abstract-method interface so tests can supply a lambda as the fake.

- [ ] **Step 2: Verify the build still passes**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/quark/Assistant.java
git commit -m "feat: add Assistant AI service interface for Gemini calls"
```

---

## Task 3: `Dispatcher` (TDD)

**Files:**
- Create: `src/main/java/com/quark/Dispatcher.java`
- Create: `src/test/java/com/quark/DispatcherTest.java`

We TDD this one because it has the only real logic in the walking skeleton.

- [ ] **Step 1: Write the failing test for `/start`**

Create `src/test/java/com/quark/DispatcherTest.java`:

```java
package com.quark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherTest {

    @Test
    void startCommandReturnsWelcomeAndDoesNotCallModel() {
        Assistant neverCalled = userMessage -> {
            throw new AssertionError("Model must not be called for /start");
        };
        Dispatcher dispatcher = new Dispatcher(neverCalled);

        String reply = dispatcher.reply("/start");

        assertTrue(reply.toLowerCase().contains("welcome"),
                "Expected welcome message, got: " + reply);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests com.quark.DispatcherTest`
Expected: compilation failure — `Dispatcher` class does not exist.

- [ ] **Step 3: Create the minimal `Dispatcher` to make the test pass**

`src/main/java/com/quark/Dispatcher.java`:

```java
package com.quark;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class Dispatcher {

    static final String WELCOME =
            "Welcome to quark. Send any message and I will reply.";

    private final Assistant assistant;

    public Dispatcher(Assistant assistant) {
        this.assistant = assistant;
    }

    public String reply(String userText) {
        if (userText.startsWith("/start")) {
            return WELCOME;
        }
        throw new UnsupportedOperationException("not yet implemented");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests com.quark.DispatcherTest`
Expected: PASS.

- [ ] **Step 5: Write the failing test for the model-delegation path**

Append a second test method to `DispatcherTest`:

```java
    @Test
    void anyOtherTextIsForwardedToTheModelAndItsReplyReturnedVerbatim() {
        Assistant echo = userMessage -> "echo: " + userMessage;
        Dispatcher dispatcher = new Dispatcher(echo);

        String reply = dispatcher.reply("hello there");

        assertEquals("echo: hello there", reply);
    }
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew test --tests com.quark.DispatcherTest`
Expected: the new test fails with `UnsupportedOperationException: not yet implemented`. The first test still passes.

- [ ] **Step 7: Implement the model-delegation branch**

In `Dispatcher.java`, replace the `throw new UnsupportedOperationException(...)` line with:

```java
        return assistant.chat(userText);
```

The final method body reads:

```java
    public String reply(String userText) {
        if (userText.startsWith("/start")) {
            return WELCOME;
        }
        return assistant.chat(userText);
    }
```

- [ ] **Step 8: Run the test to verify both tests pass**

Run: `./gradlew test --tests com.quark.DispatcherTest`
Expected: 2 tests, both PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/quark/Dispatcher.java src/test/java/com/quark/DispatcherTest.java
git commit -m "feat: add Dispatcher with /start command and model fallback"
```

---

## Task 4: Telegram DTOs

**Files:**
- Create: `src/main/java/com/quark/telegram/TelegramApi.java`

No tests — these are pure data carriers. Jackson + records take care of the wire format.

- [ ] **Step 1: Create the file with all DTOs**

`src/main/java/com/quark/telegram/TelegramApi.java`:

```java
package com.quark.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Holder for the small subset of the Telegram Bot API types we need.
 * Records are public so they can be deserialised by Jackson and used
 * by the rest of the package.
 */
public final class TelegramApi {

    private TelegramApi() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(long messageId, Chat chat, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(long updateId, Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GetUpdatesResponse(boolean ok, List<Update> result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendMessageResponse(boolean ok) {}
}
```

Notes:
- `@JsonIgnoreProperties(ignoreUnknown = true)` is essential — the Telegram API returns many fields per update; ignoring unknowns means our schema is robust as Telegram evolves.
- The Telegram API uses `snake_case` JSON keys (`update_id`, `message_id`, `chat_id`). Quarkus's default Jackson configuration **does not** translate snake_case to camelCase automatically. We handle this in step 2.

- [ ] **Step 2: Configure Jackson for snake_case in `application.properties`**

Append to `src/main/resources/application.properties`:

```properties
# Telegram returns snake_case JSON; map it to camelCase records.
quarkus.jackson.property-naming-strategy=SNAKE_CASE
```

- [ ] **Step 3: Verify the build still passes**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/quark/telegram/TelegramApi.java src/main/resources/application.properties
git commit -m "feat: add telegram bot api DTOs and snake_case jackson config"
```

---

## Task 5: `TelegramClient` REST client interface

**Files:**
- Create: `src/main/java/com/quark/telegram/TelegramClient.java`

- [ ] **Step 1: Create the file**

`src/main/java/com/quark/telegram/TelegramClient.java`:

```java
package com.quark.telegram;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "telegram")
public interface TelegramClient {

    /**
     * Long-polls Telegram for new updates.
     *
     * @param offset   pass the highest update_id seen so far plus one to
     *                 acknowledge previous updates. Pass null on the first call.
     * @param timeout  long-poll timeout in seconds (Telegram caps this around 50).
     */
    @GET
    @Path("/getUpdates")
    TelegramApi.GetUpdatesResponse getUpdates(
            @QueryParam("offset") Long offset,
            @QueryParam("timeout") int timeout);

    /**
     * Sends a text message to the given chat. We deserialise only the
     * `ok` field; everything else is ignored.
     */
    @GET
    @Path("/sendMessage")
    TelegramApi.SendMessageResponse sendMessage(
            @QueryParam("chat_id") long chatId,
            @QueryParam("text") String text);
}
```

Notes:
- `configKey = "telegram"` binds to the `quarkus.rest-client.telegram.url` property from Task 1.
- Using `GET` for `sendMessage` is intentional for simplicity: Telegram supports query-param-only invocation, and we sidestep `Content-Type`, body serialisation, and `POST` plumbing. The trade-off is that very long messages or text containing many special characters can blow past URL length limits or encoding edge cases; both are acceptable for the walking skeleton.

- [ ] **Step 2: Verify the build still passes**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/quark/telegram/TelegramClient.java
git commit -m "feat: add Telegram REST client interface"
```

---

## Task 6: `QuarkBot` polling loop

**Files:**
- Create: `src/main/java/com/quark/telegram/QuarkBot.java`

- [ ] **Step 1: Create the file**

`src/main/java/com/quark/telegram/QuarkBot.java`:

```java
package com.quark.telegram;

import com.quark.Dispatcher;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class QuarkBot {

    private static final Logger LOG = Logger.getLogger(QuarkBot.class);

    private final TelegramClient telegram;
    private final Dispatcher dispatcher;
    private final boolean enabled;
    private final int pollTimeoutSeconds;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollerThread;
    private volatile Long offset = null;

    public QuarkBot(
            @RestClient TelegramClient telegram,
            Dispatcher dispatcher,
            @ConfigProperty(name = "quark.telegram.enabled") boolean enabled,
            @ConfigProperty(name = "quark.telegram.poll-timeout-seconds") int pollTimeoutSeconds) {
        this.telegram = telegram;
        this.dispatcher = dispatcher;
        this.enabled = enabled;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("Telegram bot disabled (quark.telegram.enabled=false); not starting poller.");
            return;
        }
        LOG.info("Starting Telegram poller on a virtual thread.");
        running.set(true);
        pollerThread = Thread.ofVirtual().name("quark-telegram-poller").start(this::pollLoop);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                TelegramApi.GetUpdatesResponse response = telegram.getUpdates(offset, pollTimeoutSeconds);
                if (response == null || !response.ok() || response.result() == null) {
                    continue;
                }
                for (TelegramApi.Update update : response.result()) {
                    handle(update);
                    offset = update.updateId() + 1;
                }
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                LOG.warn("Polling iteration failed; backing off 2s before retrying.", e);
                sleepQuietly(2_000);
            }
        }
    }

    private void handle(TelegramApi.Update update) {
        TelegramApi.Message message = update.message();
        if (message == null || message.text() == null || message.chat() == null) {
            return;
        }
        long chatId = message.chat().id();
        String reply;
        try {
            reply = dispatcher.reply(message.text());
        } catch (Exception e) {
            LOG.error("Dispatcher failed for chat " + chatId, e);
            reply = "Sorry — something went wrong handling that message.";
        }
        try {
            telegram.sendMessage(chatId, reply);
        } catch (Exception e) {
            LOG.error("sendMessage failed for chat " + chatId, e);
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

Notes:
- No unit test for `QuarkBot` in this plan. It is a thin coordinator with no branching logic worth testing in isolation; the value of testing it would require mocking the REST client, which exceeds the YAGNI bar for a walking skeleton. Behaviour is verified via the existing `GreetingResourceTest` (proves the app boots with `QuarkBot` wired in) and the live integration test in Task 7.
- The bare `catch (Exception e)` in `pollLoop` is deliberate — long-polling against an external service has many failure modes (timeouts, transient 5xx, JSON deserialisation hiccups when Telegram changes a field type) and we want the loop to survive all of them. This is one of the few places where a broad catch is the right call.

- [ ] **Step 2: Verify the build still passes**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify the existing test suite still passes (proves app boots with QuarkBot wired)**

Run: `./gradlew test`
Expected: all tests PASS, including `GreetingResourceTest` (which boots the full Quarkus context). The log should include `Telegram bot disabled ... not starting poller.` because `quark.telegram.enabled` defaults to false.

If Quarkus fails to boot here, **do not** work around it by overriding `quarkus.rest-client.telegram.url` in a test `application.properties` — that override would also silently break the live integration test in Task 7. Instead, diagnose the underlying issue (most likely an empty `TELEGRAM_BOT_TOKEN` causing a malformed URL string) and fix it in `src/main/resources/application.properties` so the URL is valid even without env vars. One safe pattern: keep a placeholder default that yields a valid (if useless) URL, e.g. `quarkus.rest-client.telegram.url=https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN:0}` — `https://api.telegram.org/bot0` parses fine and is never called unless polling is enabled.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/quark/telegram/QuarkBot.java
# also include src/main/resources/application.properties if you adjusted the URL default in Step 3
git commit -m "feat: add Telegram polling loop on a virtual thread"
```

---

## Task 7: Live integration test (gated on env vars)

**Files:**
- Create: `src/test/java/com/quark/telegram/QuarkBotLiveIT.java`

This test is the manual verification mechanism for the walking skeleton. It is skipped automatically when the gating env vars are absent, so it does not affect normal `./gradlew test` runs.

- [ ] **Step 1: Create the test file**

`src/test/java/com/quark/telegram/QuarkBotLiveIT.java`:

```java
package com.quark.telegram;

import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Live integration check against Telegram + Gemini.
 *
 * Required environment variables (test is skipped if any is missing):
 *   TELEGRAM_BOT_TOKEN  — token for a real bot you control
 *   TELEGRAM_TEST_CHAT_ID — numeric chat id to send the test message to
 *                          (start a chat with your bot, then read the
 *                          chat.id from getUpdates manually once)
 *   GEMINI_API_KEY      — Google AI Studio key
 *
 * Run with:
 *   TELEGRAM_BOT_TOKEN=... TELEGRAM_TEST_CHAT_ID=... GEMINI_API_KEY=... \
 *     ./gradlew test --tests com.quark.telegram.QuarkBotLiveIT
 */
@QuarkusTest
class QuarkBotLiveIT {

    @Inject @RestClient
    TelegramClient telegram;

    @Test
    void sendsAGeminiBackedReplyToAKnownChat() {
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        String chatIdEnv = System.getenv("TELEGRAM_TEST_CHAT_ID");
        String geminiKey = System.getenv("GEMINI_API_KEY");
        assumeTrue(token != null && !token.isBlank(), "TELEGRAM_BOT_TOKEN not set");
        assumeTrue(chatIdEnv != null && !chatIdEnv.isBlank(), "TELEGRAM_TEST_CHAT_ID not set");
        assumeTrue(geminiKey != null && !geminiKey.isBlank(), "GEMINI_API_KEY not set");

        long chatId = Long.parseLong(chatIdEnv);

        TelegramApi.SendMessageResponse response = telegram.sendMessage(chatId,
                "[quark live IT] If you can read this, the Telegram path works.");
        assertNotNull(response);
        assertTrue(response.ok(),
                "Expected ok=true in Telegram response, got: " + response);
    }
}
```

Notes:
- This test exercises only the Telegram send path. It does **not** start the polling loop (Quarkus dev/test sets `quark.telegram.enabled=false` by default) and does **not** call Gemini directly — Gemini is exercised end-to-end by manually messaging the bot when it is running in dev mode (see Step 3 below).
- A more thorough integration test (poll → dispatch → reply) would require a second Telegram client account to act as a "user" sending messages to the bot. Out of scope for the walking skeleton.

- [ ] **Step 2: Verify the test is skipped without env vars**

Run: `./gradlew test --tests com.quark.telegram.QuarkBotLiveIT`
Expected: 1 test skipped (or marked as aborted) due to `assumeTrue` failure. Build is GREEN.

- [ ] **Step 3: Manual end-to-end verification (optional but strongly recommended)**

In one terminal, with real credentials:

```bash
export GEMINI_API_KEY=...
export TELEGRAM_BOT_TOKEN=...
export QUARK_TELEGRAM_ENABLED=true
./gradlew quarkusDev
```

Open Telegram, find your bot, send `/start`. Expected: the welcome message comes back within a second.

Send any other message, e.g. `What is the capital of France?`. Expected: a Gemini-generated reply arrives within a few seconds.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/quark/telegram/QuarkBotLiveIT.java
git commit -m "test: add gated live integration test for Telegram send path"
```

---

## Done

After all seven tasks, the codebase contains a working Telegram bot backed by Gemini. The Git log should look like:

```
test: add gated live integration test for Telegram send path
feat: add Telegram polling loop on a virtual thread
feat: add Telegram REST client interface
feat: add telegram bot api DTOs and snake_case jackson config
feat: add Dispatcher with /start command and model fallback
feat: add Assistant AI service interface for Gemini calls
chore: add gemini + telegram rest client deps and config
docs: project README pointing at slice 1 design
chore: add Gradle build configuration
chore: add git and docker ignore rules
docs(spec): add agent runtime slice 1 design
chore: add gradle wrapper scripts
```

### What we deliberately did not do

- **No `AgentRuntime`, no `AgentEvent`, no `ModelGateway`.** Direct injection of `Assistant` (a `@RegisterAiService` interface) into `Dispatcher`. Extracting these abstractions is the central task of plan 4.
- **No memory.** Each turn is independent. Plan 2 adds in-process memory.
- **No streaming.** Plan 3 adds Telegram edit-streaming.
- **No second provider.** Plan 5 adds NIM + `ProviderPreferenceStore`.
- **No REST/SSE.** Plan 6 adds the REST adapter.
- **No ArchUnit, no Micrometer.** Plan 7 adds observability and boundary enforcement.

This is intentional. Each subsequent plan is a small, focused change that grows the codebase toward the slice 1 design without over-investing in scaffolding up front.
