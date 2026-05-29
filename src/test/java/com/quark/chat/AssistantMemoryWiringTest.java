package com.quark.chat;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AssistantMemoryWiringTest {

    @Inject
    Assistant assistant;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Test
    void memoryMachineryIsWired() {
        assertNotNull(assistant, "Assistant must resolve");
        assertNotNull(chatMemoryStore, "LangChain4j chat memory store must be injectable");
    }
}
