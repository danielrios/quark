package com.quark.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quark.core.AgentEvent;
import com.quark.runtime.AgentRuntime;
import com.quark.adapter.telegram.TelegramMessages.EditMessageText;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the throttle gate: with a very large throttle interval, intermediate tokens are NOT
 * individually edited — they batch until the terminal TurnCompleted event fires the final flush.
 */
@QuarkusTest
@TestProfile(TelegramThrottleTest.LargeThrottleProfile.class)
class TelegramThrottleTest {

    public static class LargeThrottleProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quark.telegram.stream-throttle-ms", String.valueOf(Long.MAX_VALUE));
        }
    }

    @Inject
    TelegramStreamHandler handler;

    @InjectMock
    AgentRuntime mockRuntime;

    @InjectMock
    @RestClient
    TelegramApi mockApi;

    /**
     * With throttleMs=Long.MAX_VALUE, the gate `now - lastEdit >= throttleMs` is never true, so
     * no mid-stream edits fire. Only the terminal TurnCompleted flush produces a single edit with
     * the complete accumulated buffer.
     */
    @Test
    void tokensWithinThrottleWindowAreBatchedIntoSingleFinalEdit() {
        when(mockRuntime.execute(any()))
            .thenReturn(Multi.createFrom().iterable(java.util.List.<AgentEvent>of(
                new AgentEvent.TokenEmitted("t1", "tok1"),
                new AgentEvent.TokenEmitted("t1", "tok2"),
                new AgentEvent.TokenEmitted("t1", "tok3"),
                new AgentEvent.ModelCompleted("t1"),
                new AgentEvent.TurnCompleted("t1", "tok1tok2tok3"))));

        handler.stream(100L, 42L, "sess", "hello");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(mockApi, times(1)).editMessageText(captor.capture());
        assertEquals("tok1tok2tok3", captor.getValue().text());
        assertEquals(100L, captor.getValue().chatId());
        assertEquals(42L, captor.getValue().messageId());
    }
}
