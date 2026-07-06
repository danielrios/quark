package com.quark.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.core.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryChatMemoryStoreTest {

    @Test
    void loadUnknownSessionReturnsEmptyList() {
        var store = new InMemoryChatMemoryStore(20);
        assertTrue(store.load("nope").isEmpty());
    }

    @Test
    void appendThenLoadRoundTripsInOrder() {
        var store = new InMemoryChatMemoryStore(20);
        store.append("s1", new ChatMessage(ChatMessage.Role.USER, "hello"));
        store.append("s1", new ChatMessage(ChatMessage.Role.ASSISTANT, "hi"));

        List<ChatMessage> messages = store.load("s1");

        assertEquals(2, messages.size());
        assertEquals(ChatMessage.Role.USER, messages.get(0).role());
        assertEquals("hello", messages.get(0).text());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(1).role());
        assertEquals("hi", messages.get(1).text());
    }

    @Test
    void sessionsAreIsolated() {
        var store = new InMemoryChatMemoryStore(20);
        store.append("s1", new ChatMessage(ChatMessage.Role.USER, "from-s1"));
        store.append("s2", new ChatMessage(ChatMessage.Role.USER, "from-s2"));

        assertEquals(1, store.load("s1").size());
        assertEquals("from-s1", store.load("s1").get(0).text());
        assertEquals(1, store.load("s2").size());
        assertEquals("from-s2", store.load("s2").get(0).text());
    }

    @Test
    void deleteClearsOnlyTargetSession() {
        var store = new InMemoryChatMemoryStore(20);
        store.append("s1", new ChatMessage(ChatMessage.Role.USER, "from-s1"));
        store.append("s2", new ChatMessage(ChatMessage.Role.USER, "from-s2"));

        store.delete("s1");

        assertTrue(store.load("s1").isEmpty());
        assertEquals(1, store.load("s2").size());
    }

    @Test
    void appendBeyondBoundEvictsOldest() {
        var store = new InMemoryChatMemoryStore(3);
        for (int i = 1; i <= 5; i++) {
            store.append("s1", new ChatMessage(ChatMessage.Role.USER, "m" + i));
        }

        List<String> texts = store.load("s1").stream().map(ChatMessage::text).toList();

        assertEquals(List.of("m3", "m4", "m5"), texts);
    }

    @Test
    void loadReturnsImmutableSnapshot() {
        var store = new InMemoryChatMemoryStore(20);
        store.append("s1", new ChatMessage(ChatMessage.Role.USER, "hello"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> store.load("s1").add(new ChatMessage(ChatMessage.Role.USER, "intruder")));

        assertEquals(1, store.load("s1").size());
    }
}
