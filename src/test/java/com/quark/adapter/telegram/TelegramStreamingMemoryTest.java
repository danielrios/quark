package com.quark.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.provider.gemini.RecordingStreamingChatModel;
import com.quark.core.AgentEvent;
import com.quark.core.TurnRequest;
import com.quark.memory.ChatMemoryStore;
import com.quark.runtime.AgentRuntime;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.quarkiverse.langchain4j.ModelName;
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
 * End-to-end memory test of the runtime streaming path: {@code AgentRuntime.execute} →
 * {@code GeminiModelGateway} → {@code StreamingChatModel}, proving the runtime persists the turn
 * at completion so that the second turn replays the first turn's history.
 *
 * <p>Mirrors {@link TelegramConversationMemoryTest} but drives {@code AgentRuntime.execute}
 * directly instead of the full {@code TelegramBotRunner} dispatch path. Each turn runs inside its
 * own activated-then-terminated CDI request context, mirroring {@code TelegramBotRunner.handle()}.
 * A {@link RecordingStreamingChatModel} replaces the real Gemini streaming model via
 * {@code QuarkusMock.installMockForType} — the runtime's {@code GeminiModelGateway} resolves the
 * same mocked bean.
 *
 * <p>This guards the §8 / ADR-0007 write-back concern: if {@code AgentRuntime} does not persist
 * the streamed response to the {@code ChatMemoryStore} at completion, the second turn will not see
 * the first turn's history and this test goes red.
 */
@QuarkusTest
class TelegramStreamingMemoryTest {

    @Inject
    AgentRuntime runtime;

    @Inject
    ChatMemoryStore store;

    private final RecordingStreamingChatModel model = new RecordingStreamingChatModel();

    private static final String SESSION = "stream-mem-test";

    @BeforeEach
    void installFakeStreamingModel() {
        model.receivedTurns.clear();
        QuarkusMock.installMockForType(model, StreamingChatModel.class, ModelName.Literal.of("gemini"));
    }

    @AfterEach
    void cleanStore() {
        store.delete(SESSION);
    }

    /**
     * Drives one {@code AgentRuntime.execute} turn in its own request context, waiting for the
     * stream to complete. Mirrors the CDI context lifecycle of TelegramBotRunner.handle().
     */
    private String asStreamingUpdate(String sessionId, String text) throws InterruptedException {
        ManagedContext ctx = Arc.container().requestContext();
        ctx.activate();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            StringBuilder buffer = new StringBuilder();
            runtime.execute(TurnRequest.of(sessionId, text))
                .subscribe().with(
                    event -> {
                        if (event instanceof AgentEvent.TokenEmitted token) {
                            buffer.append(token.text());
                        }
                    },
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
