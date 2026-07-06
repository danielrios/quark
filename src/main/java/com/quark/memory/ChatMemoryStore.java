package com.quark.memory;

import com.quark.core.ChatMessage;
import java.util.List;

/**
 * Conversation-memory SPI extracted in Plan 4 (ADR 0003).
 *
 * <p>Owned by {@code AgentRuntime}; never called by adapters directly (ADR
 * 0002 boundaries).
 *
 * <p>Deliberate name collision with langchain4j's {@code
 * dev.langchain4j.store.memory.chat.ChatMemoryStore} — this quark SPI is the
 * one runtime code must import.
 */
public interface ChatMemoryStore {

    /**
     * Returns an immutable snapshot of the session's messages, oldest first.
     * Returns an empty list for an unknown session.
     */
    List<ChatMessage> load(String sessionId);

    /**
     * Appends {@code message} as the newest message for {@code sessionId}.
     * Implementations may evict the oldest messages beyond a bound.
     */
    void append(String sessionId, ChatMessage message);

    /**
     * Drops the whole session (the {@code /reset} path).
     */
    void delete(String sessionId);
}
