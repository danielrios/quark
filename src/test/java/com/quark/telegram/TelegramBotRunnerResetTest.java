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
