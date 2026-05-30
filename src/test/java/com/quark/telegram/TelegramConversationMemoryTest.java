package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.chat.RecordingChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test of the real memory pipeline:
 * {@code TelegramBotRunner.dispatch} → {@code Assistant.chat} → {@code ChatMemoryProvider}
 * → {@code ChatMemoryStore}, driven exactly as the Telegram poller drives it.
 *
 * <p>Each simulated Telegram update runs inside its own activated-then-terminated CDI request
 * context (mirroring {@code TelegramBotRunner.handle()}). A {@link RecordingChatModel} replaces
 * Gemini via {@code QuarkusMock.installMockForType} and captures the message list the AI service
 * actually sends, so we can prove the second turn carries the first turn's history — the real
 * meaning of "memory persists across requests."
 *
 * <p>This guards the root cause found while debugging PR #14: with a {@code @RequestScoped} AI
 * service (the quarkus-langchain4j default), the generated service bean's {@code @PreDestroy} runs
 * at request-context termination → {@code ChatMemoryService.clearAll()} → {@code ChatMemory.clear()}
 * → {@code ChatMemoryStore.deleteMessages(sessionId)}, wiping the session at the end of every
 * update. Only an {@code @ApplicationScoped Assistant} (isolated by an explicit {@code @MemoryId})
 * survives that. If someone reverts the scope, {@link #secondTurnSeesFirstTurnHistory()} fails.
 */
@QuarkusTest
class TelegramConversationMemoryTest {

    @Inject
    TelegramBotRunner runner;

    @Inject
    ChatMemoryStore store;

    private final RecordingChatModel model = new RecordingChatModel();

    private static final String SESSION = "conv-42";
    private static final String OTHER = "conv-99";

    @BeforeEach
    void installFakeModel() {
        model.receivedTurns.clear();
        QuarkusMock.installMockForType(model, ChatModel.class);
    }

    @AfterEach
    void cleanStore() {
        store.deleteMessages(SESSION);
        store.deleteMessages(OTHER);
    }

    /** Runs one dispatch in its own request context, like a single Telegram update. */
    private String asTelegramUpdate(String sessionId, String text) {
        ManagedContext ctx = Arc.container().requestContext();
        ctx.activate();
        try {
            return runner.dispatch(sessionId, text);
        } finally {
            ctx.terminate();
        }
    }

    @Test
    void secondTurnSeesFirstTurnHistory() {
        String r1 = asTelegramUpdate(SESSION, "My name is Daniel.");
        String r2 = asTelegramUpdate(SESSION, "What is my name?");

        // Both turns reached the model (real reply, not the "Something went wrong" fallback).
        assertEquals(2, model.receivedTurns.size(), "both turns must reach the model");
        assertEquals("ack-1", r1);
        assertEquals("ack-2", r2);

        List<ChatMessage> turn1 = model.receivedTurns.get(0);
        List<ChatMessage> turn2 = model.receivedTurns.get(1);

        assertTrue(
                turn2.size() > turn1.size(),
                "turn 2 must include prior history; turn1=" + turn1.size() + " turn2=" + turn2.size());
        assertTrue(
                turn2.toString().contains("My name is Daniel"),
                "turn 2 prompt must replay the first user message; got: " + turn2);
        assertTrue(
                turn2.toString().contains("ack-1"),
                "turn 2 prompt must replay the turn-1 AI reply; got: " + turn2);
    }

    @Test
    void resetMidConversationDropsHistory() {
        asTelegramUpdate(SESSION, "My name is Daniel.");
        String resetReply = asTelegramUpdate(SESSION, "/reset");
        asTelegramUpdate(SESSION, "What is my name?");

        assertTrue(resetReply.toLowerCase().contains("cleared"), "/reset confirms");
        assertEquals(2, model.receivedTurns.size(), "/reset must not call the model");
        assertFalse(
                model.receivedTurns.get(1).toString().contains("My name is Daniel"),
                "history before /reset must not leak into the next turn");
    }

    @Test
    void sessionsDoNotShareMemory() {
        asTelegramUpdate(SESSION, "My name is Daniel.");
        asTelegramUpdate(OTHER, "What is my name?");

        assertFalse(
                model.receivedTurns.get(1).toString().contains("My name is Daniel"),
                "a different session must not inherit memory");
    }
}
