package com.quark.chat;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-scoped in-memory chat memory store.
 *
 * <p>The default store produced by quarkus-langchain4j is not application-scoped,
 * causing memory to be lost between requests. This bean overrides it with a
 * singleton map that survives across all Telegram message handling requests.
 */
@ApplicationScoped
public class AppScopedChatMemoryStore implements ChatMemoryStore {

    private final ConcurrentHashMap<Object, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return new ArrayList<>(store.getOrDefault(memoryId, List.of()));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        store.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        store.remove(memoryId);
    }
}
