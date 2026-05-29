package com.quark.chat;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * One-shot Gemini chat. Stateless by design — no @MemoryId, so each
 * call is independent. Memory arrives in Plan 2.
 */
@RegisterAiService
public interface Assistant {

    @SystemMessage("You are quark, a concise and helpful assistant. Answer in plain text.")
    String chat(String userMessage);
}
