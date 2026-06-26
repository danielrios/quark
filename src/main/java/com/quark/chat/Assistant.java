package com.quark.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * <strong>{@code @ApplicationScoped} is load-bearing — do not remove it.</strong> A
 * {@code @RegisterAiService} defaults to {@code @RequestScoped}; such a bean is destroyed at the
 * end of each Telegram update, and its generated {@code @PreDestroy} runs
 * {@code ChatMemoryService.clearAll()} → {@code ChatMemory.clear()} →
 * {@code ChatMemoryStore.deleteMessages(sessionId)}, wiping the conversation every turn. Application
 * scope keeps the service (and its memory) alive across updates; sessions stay isolated because
 * every call carries an explicit {@code @MemoryId}. Proven by {@code TelegramConversationMemoryTest}
 * (config B vs D); see {@code docs/adr/0006-application-scoped-ai-service-for-memory.md}.
 */
@RegisterAiService
@ApplicationScoped
@SystemMessage("You are quark, a concise and helpful assistant. Answer in plain text.")
public interface Assistant {

    String chat(@MemoryId String sessionId, @UserMessage String userMessage);

    Multi<String> streamChat(@MemoryId String sessionId, @UserMessage String userMessage);
}
