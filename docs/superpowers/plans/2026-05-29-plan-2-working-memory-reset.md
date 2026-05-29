# Plan 2 — In-process working memory + `/reset` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Telegram bot per-session conversation memory so Gemini sees prior turns, and add a `/reset` command that clears one session's memory.

**Architecture:** Use `quarkus-langchain4j`'s built-in chat memory — add a `@MemoryId` parameter to the `Assistant` AI service and let the extension's default `ChatMemoryProvider` (a `MessageWindowChatMemory` over the default in-memory `ChatMemoryStore`) handle storage, keyed by the Telegram chat id. No bespoke memory class, no SPI, no persistence — this stays inside ADR 0003 Plan 2's "no new abstractions" envelope. The dispatcher (`TelegramBotRunner`) gains a small, package-visible `dispatch(sessionId, text)` seam that routes `/reset` to a store clear and everything else to the AI service; the seam exists purely so the routing is unit-testable offline.

**Tech Stack:** Java 25, Quarkus 3.35.4, `quarkus-langchain4j-ai-gemini`, LangChain4j memory (`dev.langchain4j.service.MemoryId`, `dev.langchain4j.store.memory.chat.ChatMemoryStore`), JUnit 5 + `@QuarkusTest`.

---

## Design decision recorded here (read before editing)

**Deviation from MVP spec §6.2.** The spec *suggests* a hand-rolled `ChatMemory` class wrapping `Map<String, List<ChatMessage>>`. This plan instead uses LangChain4j's built-in memory via `@MemoryId`. Rationale: it is strictly less code, idiomatic Quarkus, satisfies CLAUDE.md §6 "least power", and is *more* within ADR 0003's no-abstractions intent (we add zero classes for storage) — not less. The spec hedges its shape ("suggested structure", "proposed shape"), so this is a permitted refinement, not a contradiction. This decision is recorded in `docs/progress.md` (Task 6) and must be restated in the PR body. No ADR required.

**Out of scope for this plan (do NOT implement):** `/start` and `/status`. The MVP spec §8 lists `/start`, `/reset`, `/status` together, which tempts adding "the easy ones too." ADR 0003 scopes Plan 2 to **`/reset` only**; `/status` lands in Plan 5, `/start` is unscoped. WIP = 1 (CLAUDE.md §2): implement `/reset` and nothing else.

## Verification weight (where this plan can fail silently)

- **Loud failures (tests catch these):** a broken `@MemoryId` wiring or an unresolvable store bean fails the boot/`@QuarkusTest`. The reset test (Task 3) *is* the verification that the store handle works.
- **Silent failure (tests do NOT catch this):** an unknown `quarkus.langchain4j.*` config key does **not** fail boot — Quarkus logs an "unrecognized configuration key" warning and silently uses the default. Therefore: **correctness must not depend on the `max-messages` property.** Memory must work on the provider's default window even if that key is omitted or misspelled. Treat `max-messages=20` (Task 2) as optional tuning, confirmed in dev mode, never load-bearing.

## Test gate

CLAUDE.md §3 mandates the `quarkus-agent` MCP `devui-testing_runTests` gate. Per `docs/progress.md` ("Test gate fallback active"), that tool currently cannot detect the HTTP port, so the **active gate is `./gradlew test`** (harness pre-approved, Java 25 confirmed). Use `./gradlew test` in every verify step below. Run `./gradlew spotlessApply` before each commit. Keep dev mode (if started via the MCP) **off port 8081** — it collides with the Quarkus test port and breaks `@QuarkusTest` binding.

---

## File structure

| File | Responsibility | Action |
|------|----------------|--------|
| `src/main/java/com/quark/telegram/TelegramCommands.java` | Pure command parser: classify incoming text as `RESET` or `CHAT`. No CDI, no I/O. | Create |
| `src/test/java/com/quark/telegram/TelegramCommandsTest.java` | Unit tests for the parser. | Create |
| `src/main/java/com/quark/chat/Assistant.java` | Gemini AI service — gains `@MemoryId` so each session has its own bounded history. | Modify |
| `src/main/java/com/quark/telegram/TelegramBotRunner.java` | Dispatcher — derives `sessionId` from chat id, routes via new `dispatch()` seam, injects the store for `/reset`. | Modify |
| `src/test/java/com/quark/telegram/TelegramBotRunnerResetTest.java` | `@QuarkusTest` proving `/reset` clears that session's memory and confirms to the user. | Create |
| `src/main/resources/application.properties` | Optional memory-window tuning (non-load-bearing). | Modify |
| `README.md` | Un-mark memory + `/reset` from _(planned)_. | Modify |
| `docs/progress.md` | Task pointer → Plan 3; record the spec §6.2 deviation. | Modify |

---

## Task 1: Pure command parser (`TelegramCommands`)

**Files:**
- Create: `src/main/java/com/quark/telegram/TelegramCommands.java`
- Test: `src/test/java/com/quark/telegram/TelegramCommandsTest.java`

Telegram sends commands as plain message text. In groups it appends `@botusername` (e.g. `/reset@quark_bot`), and users may add trailing args or odd casing. The parser is a pure function so the routing rules are testable without booting Quarkus.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/quark/telegram/TelegramCommandsTest.java`:

```java
package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quark.telegram.TelegramCommands.Command;
import org.junit.jupiter.api.Test;

class TelegramCommandsTest {

    @Test
    void plainResetIsResetCommand() {
        assertEquals(Command.RESET, TelegramCommands.parse("/reset"));
    }

    @Test
    void resetWithBotMentionIsResetCommand() {
        assertEquals(Command.RESET, TelegramCommands.parse("/reset@quark_bot"));
    }

    @Test
    void resetWithTrailingArgsIsResetCommand() {
        assertEquals(Command.RESET, TelegramCommands.parse("/reset please"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals(Command.RESET, TelegramCommands.parse("   /reset  "));
    }

    @Test
    void resetIsCaseInsensitive() {
        assertEquals(Command.RESET, TelegramCommands.parse("/RESET"));
    }

    @Test
    void ordinaryTextIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse("hello there"));
    }

    @Test
    void unhandledSlashCommandIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse("/start"));
    }

    @Test
    void resetPrefixButLongerWordIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse("/resets"));
    }

    @Test
    void nullTextIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse(null));
    }

    @Test
    void blankTextIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse("   "));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.quark.telegram.TelegramCommandsTest`
Expected: FAIL — compilation error, `TelegramCommands` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/quark/telegram/TelegramCommands.java`:

```java
package com.quark.telegram;

/**
 * Pure classification of an incoming Telegram message into a {@link Command}.
 * Plan 2 handles exactly one command, {@code /reset}; everything else is a
 * normal chat turn. {@code /start} and {@code /status} are deferred (ADR 0003:
 * /status is Plan 5, /start is unscoped).
 */
public final class TelegramCommands {

    private TelegramCommands() {}

    public enum Command {
        RESET,
        CHAT
    }

    /**
     * Classifies message text. Recognises {@code /reset}, tolerating a
     * {@code @botname} suffix, trailing arguments, surrounding whitespace and
     * any casing. Anything else — including unknown slash commands — is treated
     * as a chat turn so it still reaches the model.
     */
    public static Command parse(String text) {
        if (text == null) {
            return Command.CHAT;
        }
        String first = text.strip().split("\\s+", 2)[0];
        int at = first.indexOf('@');
        if (at >= 0) {
            first = first.substring(0, at);
        }
        if (first.equalsIgnoreCase("/reset")) {
            return Command.RESET;
        }
        return Command.CHAT;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.quark.telegram.TelegramCommandsTest`
Expected: PASS — 10 tests, zero failures.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/quark/telegram/TelegramCommands.java \
        src/test/java/com/quark/telegram/TelegramCommandsTest.java
git commit -m "feat(telegram): add pure /reset command parser"
```

---

## Task 2: Per-session memory on the `Assistant`

**Files:**
- Modify: `src/main/java/com/quark/chat/Assistant.java`
- Modify: `src/main/java/com/quark/telegram/TelegramBotRunner.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/quark/chat/AssistantMemoryWiringTest.java` (create)

Add `@MemoryId` to the AI service. With a memory id present, `quarkus-langchain4j` automatically supplies its default `ChatMemoryProvider` (a bounded `MessageWindowChatMemory` over the default in-memory store), keyed per session. The dispatcher now derives a `sessionId` from the Telegram chat id and routes through a new package-visible `dispatch()` method. In this task `dispatch()` handles only the `CHAT` path; Task 3 adds `RESET`.

- [ ] **Step 1: Write the failing test (memory wiring smoke test)**

This is a boot-level smoke test: it proves the app still starts with `@MemoryId` wiring and that the LangChain4j chat-memory store bean is active and injectable. (A bad `@MemoryId` wiring fails boot loudly; this catches it.)

Create `src/test/java/com/quark/chat/AssistantMemoryWiringTest.java`:

```java
package com.quark.chat;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AssistantMemoryWiringTest {

    @Inject
    Assistant assistant;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Test
    void memoryMachineryIsWired() {
        assertNotNull(assistant, "Assistant must resolve");
        assertNotNull(chatMemoryStore, "LangChain4j chat memory store must be injectable");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.quark.chat.AssistantMemoryWiringTest`
Expected: FAIL — compilation error: `Assistant.chat` still takes one argument is fine, but the test references the store bean; the real failure here is that `TelegramBotRunner` will not yet compile against the new signature once you change it. If the test compiles and passes before any change, that only proves the store bean exists; proceed — the meaningful gate is Step 4 after the signature change.

> Note: if `@Inject ChatMemoryStore` does **not** resolve at this version, fall back to injecting the concrete default store instead: `@Inject dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore chatMemoryStore;`. Use whichever resolves; carry the same type into Task 3.

- [ ] **Step 3: Modify the `Assistant` to take a memory id**

Replace `src/main/java/com/quark/chat/Assistant.java` entirely with:

```java
package com.quark.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Gemini chat with per-session memory (Plan 2). The {@code sessionId} drives
 * LangChain4j's built-in {@code ChatMemoryProvider}: each session gets its own
 * bounded message window over the default in-memory store. Clearing a session's
 * memory is done by the dispatcher via {@code ChatMemoryStore}, not here.
 */
@RegisterAiService
public interface Assistant {

    @SystemMessage("You are quark, a concise and helpful assistant. Answer in plain text.")
    String chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
```

- [ ] **Step 4: Update the dispatcher to pass a session id through a `dispatch` seam**

In `src/main/java/com/quark/telegram/TelegramBotRunner.java`, replace the body of `handle(TelegramUpdate)` and add a package-visible `dispatch` method. The new `handle` derives the session id from the chat id and delegates; `dispatch` currently routes only `CHAT` (RESET is added in Task 3, but add the `import` and the `switch` shape now so Task 3 is a one-line addition).

Replace the existing `handle` method (lines ~76–98) with:

```java
    private void handle(TelegramUpdate update) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            IncomingText incoming = TelegramMessages.extractText(update).orElse(null);
            if (incoming == null) {
                return;
            }
            String sessionId = String.valueOf(incoming.chatId());
            String reply = dispatch(sessionId, incoming.text());
            api.sendMessage(
                    new SendMessage(incoming.chatId(), TelegramMessages.clampToTelegramLimit(reply)));
        } catch (Exception e) {
            Log.error("Failed to handle update " + update.updateId(), e);
        } finally {
            requestContext.terminate();
        }
    }

    /**
     * Routes one message for a session. Package-visible so command routing is
     * unit-testable offline without the poll loop. {@code RESET} is wired in
     * Plan 2 Task 3.
     */
    String dispatch(String sessionId, String text) {
        switch (TelegramCommands.parse(text)) {
            case RESET:
                // Wired in Task 3.
                return chat(sessionId, text);
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
```

Add this import alongside the other `com.quark.telegram.*` / `TelegramMessages.*` imports at the top of the file:

```java
import com.quark.telegram.TelegramCommands;
```

(`TelegramCommands` is in the same package, so the import is optional but harmless; include it for clarity if your spotless config does not strip it. If spotless complains about an unused/same-package import, omit this line.)

- [ ] **Step 5: Add optional (non-load-bearing) memory-window tuning**

In `src/main/resources/application.properties`, under the Gemini block, add:

```properties
# --- Memory (Plan 2) ---
# Bounded per-session window. OPTIONAL tuning only: if this key is wrong or
# removed, LangChain4j silently uses its default window — memory still works.
# Confirm the exact key in dev mode before relying on the value.
quarkus.langchain4j.chat-memory.memory-window.max-messages=20
```

- [ ] **Step 6: Run the full suite to verify it passes**

Run: `./gradlew test`
Expected: PASS — all existing tests (10) + `TelegramCommandsTest` (10) + `AssistantMemoryWiringTest` (1), zero failures, zero errors. If `AssistantMemoryWiringTest` fails on the `ChatMemoryStore` injection, switch to the `InMemoryChatMemoryStore` fallback noted in Step 2 and re-run.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/quark/chat/Assistant.java \
        src/main/java/com/quark/telegram/TelegramBotRunner.java \
        src/main/resources/application.properties \
        src/test/java/com/quark/chat/AssistantMemoryWiringTest.java
git commit -m "feat(chat): per-session conversation memory via @MemoryId"
```

---

## Task 3: `/reset` clears a session's memory

**Files:**
- Modify: `src/main/java/com/quark/telegram/TelegramBotRunner.java`
- Test: `src/test/java/com/quark/telegram/TelegramBotRunnerResetTest.java` (create)

Inject the chat memory store and make the `RESET` branch delete that session's messages, then confirm to the user. The `@QuarkusTest` seeds the store directly, calls `dispatch(sessionId, "/reset")`, and asserts the session is emptied and the confirmation returned. This test *is* the verification that the store handle is correct.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/quark/telegram/TelegramBotRunnerResetTest.java`:

```java
package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TelegramBotRunnerResetTest {

    @Inject
    TelegramBotRunner runner;

    @Inject
    ChatMemoryStore store;

    @Test
    void resetClearsSessionMemoryAndConfirms() {
        String sessionId = "9991";
        store.updateMessages(sessionId, List.of(UserMessage.from("remember this")));
        assertFalse(store.getMessages(sessionId).isEmpty(), "precondition: session has history");

        String reply = runner.dispatch(sessionId, "/reset");

        assertTrue(store.getMessages(sessionId).isEmpty(), "/reset must clear the session");
        assertTrue(reply.toLowerCase().contains("cleared"), "user must be told memory was cleared");
    }

    @Test
    void resetOnlyAffectsTheTargetSession() {
        String target = "1001";
        String other = "1002";
        store.updateMessages(target, List.of(UserMessage.from("target msg")));
        store.updateMessages(other, List.of(UserMessage.from("other msg")));

        runner.dispatch(target, "/reset");

        assertTrue(store.getMessages(target).isEmpty(), "target session cleared");
        assertFalse(store.getMessages(other).isEmpty(), "other session untouched");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.quark.telegram.TelegramBotRunnerResetTest`
Expected: FAIL — `resetClearsSessionMemoryAndConfirms` fails because the `RESET` branch still calls `chat(...)` (which returns "Something went wrong." under the test key and does not clear the store), so the session is not emptied and the reply does not contain "cleared".

- [ ] **Step 3: Wire the store and the RESET branch**

In `src/main/java/com/quark/telegram/TelegramBotRunner.java`:

Add the injection point next to the existing `@Inject Assistant assistant;` field:

```java
    @Inject
    dev.langchain4j.store.memory.chat.ChatMemoryStore chatMemoryStore;
```

> If `ChatMemoryStore` did not resolve in Task 2 and you used the concrete fallback there, use the same `InMemoryChatMemoryStore` type here for consistency.

Replace the `RESET` case in `dispatch` so it clears the store instead of chatting:

```java
            case RESET:
                chatMemoryStore.deleteMessages(sessionId);
                return "Memory cleared. Starting fresh.";
```

(Leave the `CHAT`/`default` branch and the private `chat(...)` helper unchanged.)

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `./gradlew test --tests com.quark.telegram.TelegramBotRunnerResetTest`
Expected: PASS — 2 tests, zero failures.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS — zero failures, zero errors across all test classes.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/quark/telegram/TelegramBotRunner.java \
        src/test/java/com/quark/telegram/TelegramBotRunnerResetTest.java
git commit -m "feat(telegram): /reset clears the session's conversation memory"
```

---

## Task 4: Verify end-to-end behaviour in dev mode (manual, non-blocking)

**Files:** none — runtime verification only.

CLAUDE.md §6 forbids declaring behaviour correct from code reading alone. The automated gate covers parsing and reset; this step confirms memory actually carries context across turns against the real model. Requires a live `GEMINI_API_KEY` and `TELEGRAM_BOT_TOKEN`. If credentials are unavailable in this session, record that and defer — do not block the plan on it.

- [ ] **Step 1: Start dev mode via the MCP (never foreground)**

Use `mcp__quarkus-agent__quarkus_start` with `projectDir` `/home/rios/projects/quark`. Confirm with `mcp__quarkus-agent__quarkus_status`. Ensure it is **not** on port 8081.

- [ ] **Step 2: Confirm the memory-window config key is recognised**

Check `mcp__quarkus-agent__quarkus_app_log` for an "unrecognized configuration key" warning naming `quarkus.langchain4j.chat-memory.memory-window.max-messages`. If present, the key is wrong for this version: either correct it (search the extension config) or delete the line — memory still works on the default window (it is non-load-bearing). Do not let this block.

- [ ] **Step 3: Exercise memory across turns**

In Telegram: send "My name is Dani.", then "What is my name?" — the second reply should reference "Dani". Then send `/reset`, then "What is my name?" again — the bot should no longer know. Capture the outcome.

- [ ] **Step 4: Stop dev mode**

Use `mcp__quarkus-agent__quarkus_stop`.

- [ ] **Step 5: Record the result** in `docs/progress.md` (done in Task 6).

---

## Task 5: Update README scope

**Files:**
- Modify: `README.md`

Memory and `/reset` are now real. Un-mark them; leave `/start` and `/status` as _(planned)_.

- [ ] **Step 1: Edit the scope lines**

In `README.md` MVP scope list, change:

```
* In-memory bounded conversation history per session _(planned)_
```
to:
```
* In-memory bounded conversation history per session
```

And in the commands table, change the `/reset` row's note so `/reset` is no longer marked planned (leave `/start` and `/status` marked planned). If the table marks the whole row group as planned, split it so only `/reset` is un-marked. Match the existing table formatting exactly.

- [ ] **Step 2: Commit**

```bash
./gradlew spotlessApply
git add README.md
git commit -m "docs: mark per-session memory and /reset as shipped"
```

---

## Task 6: Update progress ledger and record the deviation

**Files:**
- Modify: `docs/progress.md`

Use the `/progress` slash command if preferred; otherwise edit directly. Per CLAUDE.md §2 the ledger is authoritative state.

- [ ] **Step 1: Update the Current Task pointer**

Set "Current Task" to: Plan 2 — in-process working memory + `/reset` — **DONE** (commit refs), and "Next: Plan 3 — Telegram streaming via throttled message edits (ADR 0003); plan file not yet authored."

- [ ] **Step 2: Add a trajectory entry** at the top of "Active Trajectory Logs" recording:
  - what shipped (per-session memory via `@MemoryId`, `/reset` via `ChatMemoryStore.deleteMessages`),
  - the **deviation**: built-in LangChain4j memory used instead of spec §6.2's hand-rolled `ChatMemory`/`Map` — rationale (least code, idiomatic, within ADR 0003's no-abstractions envelope),
  - the Task 4 dev-mode verification result (or that it was deferred for lack of credentials),
  - the last test-gate result (`./gradlew test`, N tests, zero failures).

- [ ] **Step 3: Commit**

```bash
git add docs/progress.md
git commit -m "docs: close out Plan 2, point ledger at Plan 3"
```

---

## Done criteria

- `./gradlew test` reports zero failures and zero errors (CLAUDE.md §3 victory condition).
- `/reset` clears only the targeted session (proved by `TelegramBotRunnerResetTest`).
- Memory machinery boots and is injectable (`AssistantMemoryWiringTest`).
- `TelegramCommands.parse` covers reset/chat/edge cases.
- README and `docs/progress.md` updated; the spec §6.2 deviation is recorded in the ledger and must be repeated in the PR body.
- `/start` and `/status` were **not** added (WIP = 1, ADR 0003 scope).

## Self-review notes

- **Spec coverage:** §6.2 (bounded in-memory per-session history) → Tasks 2; §8 `/reset` (clears memory) → Tasks 1 + 3; §8 `/start`,`/status` → explicitly deferred per ADR 0003; §10 generic error message ("Something went wrong.") → preserved in `chat()` helper; §12 unit tests on memory/dispatch → Tasks 1–3.
- **Type consistency:** `sessionId` is a `String` everywhere (`@MemoryId String`, `String.valueOf(chatId)`, `store.deleteMessages(String)`, test keys are strings); `dispatch(String,String)`, `parse(String)→Command`, store type identical in Tasks 2 and 3.
- **No placeholders:** every code step shows full code; the only conditional is the documented `ChatMemoryStore` → `InMemoryChatMemoryStore` fallback.
