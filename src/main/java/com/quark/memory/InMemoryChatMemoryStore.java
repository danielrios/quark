package com.quark.memory;

import com.quark.core.ChatMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * In-process {@link ChatMemoryStore}.
 *
 * <p>{@code @ApplicationScoped} is load-bearing — the ADR 0006 invariant
 * (memory must outlive per-update request contexts) carried to the Plan 4
 * SPI; sessions are isolated by explicit sessionId. The bound preserves
 * Plan 2's 20-message window ({@code
 * quarkus.langchain4j.chat-memory.memory-window.max-messages}), config key
 * now {@code quark.memory.max-messages}.
 *
 * <p>Concurrency: appends are serialized per session via {@code compute}, but
 * {@code load} snapshots outside it. Safe today — the sequential poller blocks
 * per turn, so a session never loads while it appends. A Plan 6 {@code
 * GET /history/{sessionId}} overlapping an append on the same session is the
 * revisit trigger (see ADR 0006 consequences).
 */
@ApplicationScoped
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final ConcurrentHashMap<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private final int maxMessages;

    @Inject
    public InMemoryChatMemoryStore(@ConfigProperty(name = "quark.memory.max-messages", defaultValue = "20") int maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public List<ChatMessage> load(String sessionId) {
        List<ChatMessage> messages = sessions.get(sessionId);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        sessions.compute(sessionId, (id, existing) -> {
            List<ChatMessage> messages = existing == null ? new ArrayList<>() : existing;
            messages.add(message);
            while (messages.size() > maxMessages) {
                messages.remove(0);
            }
            return messages;
        });
    }

    @Override
    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }
}
