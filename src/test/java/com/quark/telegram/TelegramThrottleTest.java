package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quark.chat.Assistant;
import com.quark.telegram.TelegramMessages.EditMessageText;
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
 * individually edited — they batch until onComplete fires the final flush.
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
    Assistant mockAssistant;

    @InjectMock
    @RestClient
    TelegramApi mockApi;

    /**
     * With throttleMs=Long.MAX_VALUE, the gate `now - lastEdit >= throttleMs` is never true, so
     * no mid-stream edits fire. Only the onComplete flush produces a single edit with the complete
     * accumulated buffer.
     */
    @Test
    void tokensWithinThrottleWindowAreBatchedIntoSingleFinalEdit() {
        when(mockAssistant.streamChat(any(), any()))
            .thenReturn(Multi.createFrom().items("tok1", "tok2", "tok3"));

        handler.stream(100L, 42L, "sess", "hello");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(mockApi, times(1)).editMessageText(captor.capture());
        assertEquals("tok1tok2tok3", captor.getValue().text());
        assertEquals(100L, captor.getValue().chatId());
        assertEquals(42L, captor.getValue().messageId());
    }
}
