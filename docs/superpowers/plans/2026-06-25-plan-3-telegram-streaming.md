# Plan 3 — Telegram Streaming via Throttled Message Edits

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the one-shot Telegram reply with live streaming — a "…" placeholder appears immediately, then Gemini tokens edit it in real time at a throttled ~750 ms cadence.

**Architecture:** `Assistant.streamChat()` returns `Multi<String>` (the correct Quarkus LangChain4j streaming return type — **not** `TokenStream`). A new `TelegramStreamHandler` bean owns send-placeholder → subscribe → throttle-edit → final-flush. `TelegramBotRunner.handle()` routes CHAT to the stream handler; the existing blocking `dispatch()` is left unchanged so memory/reset tests keep passing. `CountDownLatch` blocks the virtual thread for the full stream duration, keeping the CDI request context alive.

**Tech Stack:** Quarkus 3.35.4, Java 25, `io.smallrye.mutiny.Multi`, `quarkus-junit5-mockito` (new test dep), MicroProfile REST Client.

---

## File Map

| File | Status | Responsibility |
|------|--------|----------------|
| `build.gradle.kts` | Modify | Add `quarkus-junit5-mockito` test dep |
| `src/main/java/com/quark/telegram/TelegramMessages.java` | Modify | Add `SendMessageResponse`, `MessageResult`, `EditMessageText` records |
| `src/main/java/com/quark/telegram/TelegramApi.java` | Modify | `sendMessage` returns `SendMessageResponse`; add `editMessageText` |
| `src/main/java/com/quark/chat/Assistant.java` | Modify | Add `Multi<String> streamChat()` method |
| `src/main/java/com/quark/telegram/TelegramStreamHandler.java` | **Create** | Streaming loop: placeholder → subscribe → throttle → flush |
| `src/main/java/com/quark/telegram/TelegramBotRunner.java` | Modify | Route CHAT in `handle()` to `TelegramStreamHandler`; `dispatch()` unchanged |
| `src/main/resources/application.properties` | Modify | Add `quark.telegram.stream-throttle-ms=750` |
| `src/test/java/com/quark/telegram/TelegramStreamHandlerTest.java` | **Create** | 4 tests covering streaming wiring, failure recovery, error replace, clamping |

---

## Task 0: Baseline gate

Before any change, confirm the 26-test suite is green.

- [ ] **Step 0.1: Run tests**

  ```bash
  ./gradlew test --rerun-tasks
  ```

  Expected: `BUILD SUCCESSFUL` with 26 tests, 0 failures.

---

## Task 1: Add `quarkus-junit5-mockito` test dependency

**Files:** `build.gradle.kts`

- [ ] **Step 1.1: Add the dependency**

  In `build.gradle.kts`, inside the `dependencies { }` block, add after the existing `testImplementation` lines:

  ```kotlin
  testImplementation("io.quarkus:quarkus-junit5-mockito")
  ```

- [ ] **Step 1.2: Verify it compiles**

  ```bash
  ./gradlew compileTestJava
  ```

  Expected: `BUILD SUCCESSFUL`. No test run yet.

- [ ] **Step 1.3: Commit**

  ```bash
  git add build.gradle.kts
  git commit -m "test: add quarkus-junit5-mockito for TelegramStreamHandler mocking"
  ```

---

## Task 2: Add new DTOs to `TelegramMessages` and update `TelegramApi`

**Files:**
- Modify: `src/main/java/com/quark/telegram/TelegramMessages.java`
- Modify: `src/main/java/com/quark/telegram/TelegramApi.java`

- [ ] **Step 2.1: Add three records to `TelegramMessages`**

  In `TelegramMessages.java`, add these three records alongside the existing ones (after the `SendMessage` record, before `IncomingText`):

  ```java
  public record SendMessageResponse(boolean ok, MessageResult result) {}

  public record MessageResult(@JsonProperty("message_id") long messageId) {}

  public record EditMessageText(
      @JsonProperty("chat_id") long chatId,
      @JsonProperty("message_id") long messageId,
      String text) {}
  ```

  Telegram's `sendMessage` response contains many more fields. Quarkus's default Jackson configuration (`FAIL_ON_UNKNOWN_PROPERTIES=false`) ignores extra fields, so no additional annotation is needed.

- [ ] **Step 2.2: Update `TelegramApi`**

  Replace the existing `sendMessage` signature and add `editMessageText`. The full updated interface:

  ```java
  package com.quark.telegram;

  import com.quark.telegram.TelegramMessages.EditMessageText;
  import com.quark.telegram.TelegramMessages.GetUpdatesResponse;
  import com.quark.telegram.TelegramMessages.SendMessage;
  import com.quark.telegram.TelegramMessages.SendMessageResponse;
  import jakarta.ws.rs.GET;
  import jakarta.ws.rs.POST;
  import jakarta.ws.rs.Path;
  import jakarta.ws.rs.QueryParam;
  import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

  @RegisterRestClient(configKey = "telegram")
  public interface TelegramApi {

      @GET
      @Path("/getUpdates")
      GetUpdatesResponse getUpdates(@QueryParam("offset") long offset, @QueryParam("timeout") int timeout);

      @POST
      @Path("/sendMessage")
      SendMessageResponse sendMessage(SendMessage message);

      @POST
      @Path("/editMessageText")
      void editMessageText(EditMessageText edit);
  }
  ```

- [ ] **Step 2.3: Fix the compile error in `TelegramBotRunner`**

  `TelegramBotRunner.handle()` currently calls `api.sendMessage(...)` and ignores its return value (it was `void`). Now it returns `SendMessageResponse`. The existing `handle()` still compiles because Java allows ignoring return values — no change needed here. Verify:

  ```bash
  ./gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2.4: Run existing tests to confirm no regression**

  ```bash
  ./gradlew test
  ```

  Expected: 26 tests, 0 failures.

- [ ] **Step 2.5: Commit**

  ```bash
  git add src/main/java/com/quark/telegram/TelegramMessages.java \
          src/main/java/com/quark/telegram/TelegramApi.java
  git commit -m "feat(telegram): add streaming DTOs and editMessageText endpoint"
  ```

---

## Task 3: Add `streamChat()` to `Assistant`

**Files:** `src/main/java/com/quark/chat/Assistant.java`

`Multi<String>` is the correct return type for streaming in Quarkus LangChain4j `@RegisterAiService` methods (verified against official docs). `TokenStream` is only for the non-Quarkus `AiServices.create()` path.

- [ ] **Step 3.1: Add the streaming method**

  Replace `Assistant.java` with:

  ```java
  package com.quark.chat;

  import dev.langchain4j.service.MemoryId;
  import dev.langchain4j.service.SystemMessage;
  import dev.langchain4j.service.UserMessage;
  import io.quarkiverse.langchain4j.RegisterAiService;
  import io.smallrye.mutiny.Multi;
  import jakarta.enterprise.context.ApplicationScoped;

  /**
   * <strong>{@code @ApplicationScoped} is load-bearing — do not remove it.</strong> A
   * {@code @RegisterAiService} defaults to {@code @RequestScoped}; such a bean is destroyed at the
   * end of each Telegram update, and its generated {@code @PreDestroy} runs
   * {@code ChatMemoryService.clearAll()} → {@code ChatMemory.clear()} →
   * {@code ChatMemoryStore.deleteMessages(sessionId)}, wiping the conversation every turn. Application
   * scope keeps the service (and its memory) alive across updates; sessions stay isolated because
   * every call carries an explicit {@code @MemoryId}. Proven by {@code TelegramConversationMemoryTest}
   * (config B vs D); see {@code docs/adr/0006-application-scoped-ai-service-for-memory.md}.
   */
  @RegisterAiService
  @ApplicationScoped
  public interface Assistant {

      @SystemMessage("You are quark, a concise and helpful assistant. Answer in plain text.")
      String chat(@MemoryId String sessionId, @UserMessage String userMessage);

      @SystemMessage("You are quark, a concise and helpful assistant. Answer in plain text.")
      Multi<String> streamChat(@MemoryId String sessionId, @UserMessage String userMessage);
  }
  ```

- [ ] **Step 3.2: Compile**

  ```bash
  ./gradlew compileJava
  ```

  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3.3: Run tests**

  ```bash
  ./gradlew test
  ```

  Expected: 26 tests, 0 failures. `AssistantMemoryWiringTest` checks injection resolves — adding a method does not break it.

- [ ] **Step 3.4: Commit**

  ```bash
  git add src/main/java/com/quark/chat/Assistant.java
  git commit -m "feat(chat): add Multi<String> streamChat() to Assistant"
  ```

---

## Task 4: Write `TelegramStreamHandlerTest` (failing) and implement `TelegramStreamHandler` (TDD)

**Files:**
- Create: `src/test/java/com/quark/telegram/TelegramStreamHandlerTest.java`
- Create: `src/main/java/com/quark/telegram/TelegramStreamHandler.java`
- Modify: `src/main/resources/application.properties`

### Step 4a: Add test throttle config

- [ ] **Step 4.1: Add to `application.properties`**

  Append these two lines to `src/main/resources/application.properties`:

  ```properties
  # --- Streaming (Plan 3) ---
  quark.telegram.stream-throttle-ms=750
  %test.quark.telegram.stream-throttle-ms=0
  ```

  Setting `0` in the test profile means every token triggers an edit — no time-dependency in tests.

### Step 4b: Write the failing test class

- [ ] **Step 4.2: Create `TelegramStreamHandlerTest.java`**

  Create `src/test/java/com/quark/telegram/TelegramStreamHandlerTest.java`:

  ```java
  package com.quark.telegram;

  import static org.junit.jupiter.api.Assertions.assertTrue;
  import static org.junit.jupiter.api.Assertions.assertEquals;
  import static org.mockito.ArgumentMatchers.any;
  import static org.mockito.Mockito.atLeastOnce;
  import static org.mockito.Mockito.doNothing;
  import static org.mockito.Mockito.doThrow;
  import static org.mockito.Mockito.times;
  import static org.mockito.Mockito.verify;
  import static org.mockito.Mockito.when;

  import com.quark.chat.Assistant;
  import com.quark.telegram.TelegramMessages.EditMessageText;
  import com.quark.telegram.TelegramMessages.MessageResult;
  import com.quark.telegram.TelegramMessages.SendMessageResponse;
  import io.quarkus.test.InjectMock;
  import io.quarkus.test.junit.QuarkusTest;
  import io.smallrye.mutiny.Multi;
  import jakarta.inject.Inject;
  import java.util.List;
  import org.eclipse.microprofile.rest.client.inject.RestClient;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.mockito.ArgumentCaptor;

  @QuarkusTest
  class TelegramStreamHandlerTest {

      @Inject
      TelegramStreamHandler handler;

      @InjectMock
      Assistant mockAssistant;

      @InjectMock
      @RestClient
      TelegramApi mockApi;

      @BeforeEach
      void stubSendMessage() {
          when(mockApi.sendMessage(any()))
              .thenReturn(new SendMessageResponse(true, new MessageResult(42L)));
      }

      /**
       * With throttleMs=0, every token triggers an edit. The final onComplete flush also fires.
       * The buffer must accumulate across all tokens so the last edit has the complete text.
       */
      @Test
      void tokensAccumulateAndFinalFlushHasCompleteBuffer() {
          when(mockAssistant.streamChat(any(), any()))
              .thenReturn(Multi.createFrom().items("Hello", " ", "World"));

          handler.stream(100L, 42L, "sess", "hello");

          ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
          verify(mockApi, atLeastOnce()).editMessageText(captor.capture());

          List<EditMessageText> edits = captor.getAllValues();
          // Last edit (onComplete final flush) must have the complete buffer
          EditMessageText last = edits.get(edits.size() - 1);
          assertEquals("Hello World", last.text());
          assertEquals(100L, last.chatId());
          assertEquals(42L, last.messageId());
      }

      /**
       * A mid-stream editMessageText failure must be swallowed. The stream must complete and
       * the final flush in onComplete must still fire with the full accumulated buffer.
       */
      @Test
      void midStreamEditFailureIsSwallowedAndStreamCompletes() {
          when(mockAssistant.streamChat(any(), any()))
              .thenReturn(Multi.createFrom().items("tok1", "tok2"));
          doThrow(new RuntimeException("429 rate limit"))
              .doNothing()
              .doNothing()
              .when(mockApi).editMessageText(any());

          // Must not throw
          handler.stream(100L, 42L, "sess", "hello");

          ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
          // tok1 (throws), tok2 (ok), onComplete (ok) = 3 total attempts
          verify(mockApi, times(3)).editMessageText(captor.capture());
          // Final edit (index 2) has the full buffer
          assertEquals("tok1tok2", captor.getAllValues().get(2).text());
      }

      /**
       * When the stream itself fails (auth error, timeout, etc.), the placeholder message
       * must be replaced with "Something went wrong." — not left as "…".
       */
      @Test
      void streamErrorReplacesMessageWithErrorText() {
          when(mockAssistant.streamChat(any(), any()))
              .thenReturn(Multi.createFrom().failure(new RuntimeException("auth error")));

          handler.stream(100L, 42L, "sess", "hello");

          ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
          verify(mockApi, times(1)).editMessageText(captor.capture());
          assertEquals("Something went wrong.", captor.getValue().text());
      }

      /**
       * When accumulated tokens exceed 4096 chars, every editMessageText call must receive
       * clamped text of at most 4096 characters (matching Telegram's hard limit).
       */
      @Test
      void bufferIsClamped() {
          String bigToken = "x".repeat(5000);
          when(mockAssistant.streamChat(any(), any()))
              .thenReturn(Multi.createFrom().items(bigToken));

          handler.stream(100L, 42L, "sess", "hello");

          ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
          verify(mockApi, atLeastOnce()).editMessageText(captor.capture());
          captor.getAllValues().forEach(e ->
              assertTrue(e.text().length() <= 4096,
                  "edit text must be <= 4096 chars, got " + e.text().length()));
      }
  }
  ```

- [ ] **Step 4.3: Verify the test file fails to compile (class not found)**

  ```bash
  ./gradlew compileTestJava 2>&1 | grep -i "error"
  ```

  Expected: compilation error — `TelegramStreamHandler` does not exist yet.

### Step 4c: Implement `TelegramStreamHandler`

- [ ] **Step 4.4: Create `TelegramStreamHandler.java`**

  Create `src/main/java/com/quark/telegram/TelegramStreamHandler.java`:

  ```java
  package com.quark.telegram;

  import com.quark.chat.Assistant;
  import com.quark.telegram.TelegramMessages.EditMessageText;
  import io.quarkus.logging.Log;
  import jakarta.enterprise.context.ApplicationScoped;
  import jakarta.inject.Inject;
  import java.util.concurrent.CountDownLatch;
  import org.eclipse.microprofile.config.inject.ConfigProperty;
  import org.eclipse.microprofile.rest.client.inject.RestClient;

  /**
   * Streaming loop for Telegram: sends a placeholder message, then edits it with accumulated tokens
   * from the LLM, throttled to avoid Telegram's rate limit. Blocks the calling virtual thread via
   * CountDownLatch so the CDI request context in TelegramBotRunner.handle() stays active for the
   * full stream duration.
   *
   * <p>buffer and lastEdit are method-local — this bean is @ApplicationScoped (singleton) and
   * stream() may be called concurrently for different users.
   */
  @ApplicationScoped
  public class TelegramStreamHandler {

      @Inject
      @RestClient
      TelegramApi api;

      @Inject
      Assistant assistant;

      @ConfigProperty(name = "quark.telegram.stream-throttle-ms", defaultValue = "750")
      long throttleMs;

      public void stream(long chatId, long messageId, String sessionId, String userText) {
          CountDownLatch latch = new CountDownLatch(1);
          StringBuilder buffer = new StringBuilder();
          long[] lastEdit = {System.currentTimeMillis()};

          assistant.streamChat(sessionId, userText)
              .subscribe().with(
                  token -> {
                      buffer.append(token);
                      long now = System.currentTimeMillis();
                      if (now - lastEdit[0] >= throttleMs) {
                          tryEdit(chatId, messageId,
                              TelegramMessages.clampToTelegramLimit(buffer.toString()));
                          lastEdit[0] = now;
                      }
                  },
                  error -> {
                      Log.error("Stream error for session " + sessionId, error);
                      tryEdit(chatId, messageId, "Something went wrong.");
                      latch.countDown();
                  },
                  () -> {
                      tryEdit(chatId, messageId,
                          TelegramMessages.clampToTelegramLimit(buffer.toString()));
                      latch.countDown();
                  });

          try {
              latch.await();
          } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
          }
      }

      private void tryEdit(long chatId, long messageId, String text) {
          try {
              api.editMessageText(new EditMessageText(chatId, messageId, text));
          } catch (Exception e) {
              Log.warn("editMessageText failed (chatId=" + chatId + ", msgId=" + messageId + ")", e);
          }
      }
  }
  ```

- [ ] **Step 4.5: Compile and run the new tests**

  ```bash
  ./gradlew test --tests "com.quark.telegram.TelegramStreamHandlerTest"
  ```

  Expected: 4 tests, 0 failures.

- [ ] **Step 4.6: Run full suite to confirm no regression**

  ```bash
  ./gradlew test
  ```

  Expected: 30 tests, 0 failures.

- [ ] **Step 4.7: Commit**

  ```bash
  git add src/main/java/com/quark/telegram/TelegramStreamHandler.java \
          src/test/java/com/quark/telegram/TelegramStreamHandlerTest.java \
          src/main/resources/application.properties
  git commit -m "feat(telegram): TelegramStreamHandler with throttled streaming loop (TDD, 4 tests)"
  ```

---

## Task 5: Wire streaming into `TelegramBotRunner.handle()`

**Files:** `src/main/java/com/quark/telegram/TelegramBotRunner.java`

The existing `dispatch()` method is left completely unchanged — memory and reset tests continue to call it directly via the blocking path.

- [ ] **Step 5.1: Update `TelegramBotRunner`**

  Full replacement of `TelegramBotRunner.java`:

  ```java
  package com.quark.telegram;

  import com.quark.chat.Assistant;
  import com.quark.telegram.TelegramMessages.GetUpdatesResponse;
  import com.quark.telegram.TelegramMessages.IncomingText;
  import com.quark.telegram.TelegramMessages.SendMessage;
  import com.quark.telegram.TelegramMessages.SendMessageResponse;
  import com.quark.telegram.TelegramMessages.TelegramUpdate;
  import io.quarkus.arc.Arc;
  import io.quarkus.arc.ManagedContext;
  import io.quarkus.logging.Log;
  import io.quarkus.runtime.ShutdownEvent;
  import io.quarkus.runtime.StartupEvent;
  import jakarta.enterprise.context.ApplicationScoped;
  import jakarta.enterprise.event.Observes;
  import dev.langchain4j.store.memory.chat.ChatMemoryStore;
  import jakarta.inject.Inject;
  import java.util.List;
  import org.eclipse.microprofile.config.inject.ConfigProperty;
  import org.eclipse.microprofile.rest.client.inject.RestClient;

  @ApplicationScoped
  public class TelegramBotRunner {

      @Inject
      @RestClient
      TelegramApi api;

      @Inject
      Assistant assistant;

      @Inject
      ChatMemoryStore chatMemoryStore;

      @Inject
      TelegramStreamHandler streamHandler;

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
                  Log.error("Telegram poll iteration failed", e);
                  sleepQuietly(1000);
              }
          }
      }

      private void handle(TelegramUpdate update) {
          ManagedContext requestContext = Arc.container().requestContext();
          requestContext.activate();
          try {
              IncomingText incoming = TelegramMessages.extractText(update).orElse(null);
              if (incoming == null) {
                  return;
              }
              String sessionId = String.valueOf(incoming.chatId());
              long chatId = incoming.chatId();

              if (TelegramCommands.parse(incoming.text()) == TelegramCommands.Command.RESET) {
                  String reply = dispatch(sessionId, incoming.text());
                  api.sendMessage(new SendMessage(chatId, reply));
              } else {
                  SendMessageResponse placeholder = api.sendMessage(new SendMessage(chatId, "…"));
                  streamHandler.stream(chatId, placeholder.result().messageId(), sessionId, incoming.text());
              }
          } catch (Exception e) {
              Log.error("Failed to handle update " + update.updateId(), e);
          } finally {
              requestContext.terminate();
          }
      }

      // Package-private: test seam used by TelegramBotRunnerResetTest and TelegramConversationMemoryTest.
      // Handles commands and blocking chat. The live path uses handle() → TelegramStreamHandler.
      String dispatch(String sessionId, String text) {
          switch (TelegramCommands.parse(text)) {
              case RESET:
                  chatMemoryStore.deleteMessages(sessionId);
                  return "Memory cleared. Starting fresh.";
              case CHAT:
              default:
                  return chat(sessionId, text);
          }
      }

      private String chat(String sessionId, String userMessage) {
          try {
              return assistant.chat(sessionId, userMessage);
          } catch (Exception e) {
              Log.error("Gemini call failed", e);
              return "Something went wrong.";
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

- [ ] **Step 5.2: Run the full test suite**

  ```bash
  ./gradlew test
  ```

  Expected: 30 tests, 0 failures. All existing reset and memory tests pass because `dispatch()` is unchanged.

- [ ] **Step 5.3: Commit**

  ```bash
  git add src/main/java/com/quark/telegram/TelegramBotRunner.java
  git commit -m "feat(telegram): route CHAT through streaming in handle(), keep dispatch() for tests"
  ```

---

## Task 6: Verify, spotless, final commit

- [ ] **Step 6.1: Run spotless and tests together**

  ```bash
  ./gradlew spotlessApply test
  ```

  Expected: `BUILD SUCCESSFUL`, 30 tests, 0 failures.

- [ ] **Step 6.2: Commit formatting fixes if any**

  ```bash
  git add -u
  git diff --cached --quiet || git commit -m "style: spotless"
  ```

- [ ] **Step 6.3: Record progress**

  Run `/progress Plan 3 complete — streaming via TelegramStreamHandler, 30 tests green`

---

## Self-Review Checklist

**Spec coverage:**
- [x] §2 `Multi<String> streamChat()` alongside `String chat()` → Task 3
- [x] §2 `TelegramStreamHandler` new bean → Task 4
- [x] §2 `sendMessage` returns `SendMessageResponse` → Task 2
- [x] §2 `editMessageText` added → Task 2
- [x] §2 `SendMessageResponse`, `MessageResult`, `EditMessageText` records → Task 2
- [x] §2 throttle via `@ConfigProperty` (750ms default, 0 in tests) → Task 4 + Task 4.1
- [x] §2 `clampToTelegramLimit()` reused → `TelegramStreamHandler.stream()` calls it
- [x] §4 `CountDownLatch` blocks virtual thread → `TelegramStreamHandler.stream()`
- [x] §4 `buffer` and `lastEdit` method-local → noted in class comment, enforced in code
- [x] §6 mid-stream edit failures swallowed → `tryEdit()` catches and logs
- [x] §6 buffer clamped → `clampToTelegramLimit()` called in `tryEdit` path
- [x] §6 `onError` → "Something went wrong." → error callback
- [x] §6 `sendMessage` failure → existing `handle()` catch block
- [x] §7 `%test.quark.telegram.stream-throttle-ms=0` → Task 4.1
- [x] §8 test 1 (buffer accumulation + final flush) → `tokensAccumulateAndFinalFlushHasCompleteBuffer`
- [x] §8 test 2 (final flush always runs) → covered in same test (last edit = complete buffer)
- [x] §8 test 3 (mid-stream failure recovery) → `midStreamEditFailureIsSwallowedAndStreamCompletes`
- [x] §8 test 4 (onError) → `streamErrorReplacesMessageWithErrorText`
- [x] §8 test 5 (buffer clamped) → `bufferIsClamped`

**Type consistency:**
- `EditMessageText(long chatId, long messageId, String text)` — defined in Task 2, used in Task 4 (`TelegramStreamHandler`) ✓
- `SendMessageResponse(boolean ok, MessageResult result)` — defined in Task 2, used in Task 5 (`handle()`) ✓
- `MessageResult(long messageId)` — defined in Task 2, accessed via `placeholder.result().messageId()` in Task 5 ✓
- `Multi<String> streamChat(String sessionId, String userMessage)` — defined in Task 3, called in Task 4 ✓
- `TelegramStreamHandler.stream(long chatId, long messageId, String sessionId, String userText)` — defined in Task 4, called in Task 5 ✓
