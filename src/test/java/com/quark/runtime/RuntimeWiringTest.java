package com.quark.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.memory.ChatMemoryStore;
import com.quark.memory.preference.ProviderPreferenceStore;
import com.quark.provider.ModelGateway;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

/**
 * CDI wiring smoke for Plan 5 runtime seams — verifies both @Named gateways resolve,
 * ProviderPreferenceStore wires, and AgentRuntime boots with the @Any Instance pattern.
 */
@QuarkusTest
class RuntimeWiringTest {

    @Inject
    AgentRuntime runtime;

    @Inject
    ChatMemoryStore store;

    @Inject
    ProviderPreferenceStore preferenceStore;

    @Inject
    @Any
    Instance<ModelGateway> gateways;

    @Test
    void runtimeMachineryIsWired() {
        assertNotNull(runtime, "AgentRuntime must resolve");
        assertNotNull(store, "ChatMemoryStore must resolve");
        assertNotNull(preferenceStore, "ProviderPreferenceStore must resolve");
    }

    @Test
    void geminiGatewayResolvableByName() {
        var selected = gateways.select(new Named() {
            @Override public String value() { return "gemini"; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return Named.class;
            }
        });
        assertTrue(selected.isResolvable(), "@Named(\"gemini\") ModelGateway must resolve");
    }

    @Test
    void nimGatewayResolvableByName() {
        var selected = gateways.select(new Named() {
            @Override public String value() { return "nim"; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return Named.class;
            }
        });
        assertTrue(selected.isResolvable(), "@Named(\"nim\") ModelGateway must resolve");
    }
}
