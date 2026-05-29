package com.quark.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

import jakarta.enterprise.context.ApplicationScoped;

@RegisterAiService
@ApplicationScoped
public interface Assistant {

    @SystemMessage("You are quark, a concise and helpful assistant. Answer in plain text.")
    String chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
