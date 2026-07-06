package com.quark.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.core.ChatMessage;
import com.quark.memory.ChatMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TelegramBotRunnerResetTest {

    @Inject
    TelegramBotRunner runner;

    @Inject
    ChatMemoryStore store;

    @AfterEach
    void cleanStore() {
        store.delete("9991");
        store.delete("1001");
        store.delete("1002");
    }

    @Test
    void resetClearsSessionMemoryAndConfirms() {
        String sessionId = "9991";
        store.append(sessionId, new ChatMessage(ChatMessage.Role.USER, "remember this"));
        assertFalse(store.load(sessionId).isEmpty(), "precondition: session has history");

        String reply = runner.dispatch(sessionId, "/reset");

        assertTrue(store.load(sessionId).isEmpty(), "/reset must clear the session");
        assertTrue(reply.toLowerCase().contains("cleared"), "user must be told memory was cleared");
    }

    @Test
    void resetOnlyAffectsTheTargetSession() {
        String target = "1001";
        String other = "1002";
        store.append(target, new ChatMessage(ChatMessage.Role.USER, "target msg"));
        store.append(other, new ChatMessage(ChatMessage.Role.USER, "other msg"));

        runner.dispatch(target, "/reset");

        assertTrue(store.load(target).isEmpty(), "target session cleared");
        assertFalse(store.load(other).isEmpty(), "other session untouched");
    }
}
