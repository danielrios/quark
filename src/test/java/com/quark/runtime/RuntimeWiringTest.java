package com.quark.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.quark.memory.ChatMemoryStore;
import com.quark.provider.ModelGateway;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * CDI wiring smoke for the Plan 4 runtime seams — replaces {@code AssistantMemoryWiringTest}; also
 * proves {@link com.quark.provider.gemini.GeminiModelGateway} instantiates against the
 * {@code %test} config {@code StreamingChatModel} bean without any {@code @RegisterAiService}.
 */
@QuarkusTest
class RuntimeWiringTest {

    @Inject
    AgentRuntime runtime;

    @Inject
    ModelGateway gateway;

    @Inject
    ChatMemoryStore store;

    @Test
    void runtimeMachineryIsWired() {
        assertNotNull(runtime, "AgentRuntime must resolve");
        assertNotNull(gateway, "ModelGateway must resolve (GeminiModelGateway over the StreamingChatModel bean)");
        assertNotNull(store, "quark ChatMemoryStore must resolve (InMemoryChatMemoryStore)");
    }
}
