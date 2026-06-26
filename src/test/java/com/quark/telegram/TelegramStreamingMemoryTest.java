package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.chat.Assistant;
import com.quark.chat.RecordingStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end memory test for the streaming path: verifies that cross-turn history is correctly
 * accumulated when using {@code Assistant.streamChat()} (the production CHAT path after Plan 3).
 *
 * <p>Mirrors {@link TelegramConversationMemoryTest} but drives the streaming method instead of
 * the blocking {@code chat()}. Each turn runs inside its own activated-then-terminated CDI request
 * context, mirroring {@code TelegramBotRunner.handle()}. A {@link RecordingStreamingChatModel}
 * replaces the real Gemini streaming model via {@code QuarkusMock.installMockForType}.
 *
 * <p>This guards the §8 / ADR-0006 concern: if the generated {@code @RegisterAiService}
 * implementation does not write the streaming response to memory at completion, the second turn
 * will not see the first turn's history and this test goes red.
 */
@QuarkusTest
class TelegramStreamingMemoryTest {

    @Inject
    Assistant assistant;

    @Inject
    ChatMemoryStore store;

    private final RecordingStreamingChatModel model = new RecordingStreamingChatModel();

    private static final String SESSION = "stream-mem-test";

    @BeforeEach
    void installFakeStreamingModel() {
        model.receivedTurns.clear();
        QuarkusMock.installMockForType(model, StreamingChatModel.class);
    }

    @AfterEach
    void cleanStore() {
        store.deleteMessages(SESSION);
    }

    /**
     * Drives one streamChat() call in its own request context, waiting for the stream to complete.
     * Mirrors the CDI context lifecycle of TelegramBotRunner.handle().
     */
    private String asStreamingUpdate(String sessionId, String text) throws InterruptedException {
        ManagedContext ctx = Arc.container().requestContext();
        ctx.activate();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            StringBuilder buffer = new StringBuilder();
            assistant.streamChat(sessionId, text)
                .subscribe().with(
                    buffer::append,
                    err -> latch.countDown(),
                    latch::countDown);
            latch.await(10, TimeUnit.SECONDS);
            return buffer.toString();
        } finally {
            ctx.terminate();
        }
    }

    @Test
    void secondStreamingTurnSeesFirstTurnHistory() throws InterruptedException {
        String r1 = asStreamingUpdate(SESSION, "My name is Daniel.");
        String r2 = asStreamingUpdate(SESSION, "What is my name?");

        assertEquals(2, model.receivedTurns.size(), "both turns must reach the streaming model");
        assertEquals("ack-1", r1);
        assertEquals("ack-2", r2);

        List<?> turn1 = model.receivedTurns.get(0);
        List<?> turn2 = model.receivedTurns.get(1);

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
}
