package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quark.chat.Assistant;
import com.quark.telegram.TelegramMessages.EditMessageText;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@QuarkusTest
class TelegramStreamHandlerTest {

    @Inject
    TelegramStreamHandler handler;

    @InjectMock
    Assistant mockAssistant;

    @InjectMock
    @RestClient
    TelegramApi mockApi;

    /**
     * With throttleMs=0, every token triggers an edit. The final onComplete flush also fires.
     * The buffer must accumulate across all tokens so the last edit has the complete text.
     */
    @Test
    void tokensAccumulateAndFinalFlushHasCompleteBuffer() {
        when(mockAssistant.streamChat(any(), any()))
            .thenReturn(Multi.createFrom().items("Hello", " ", "World"));

        handler.stream(100L, 42L, "sess", "hello");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(mockApi, atLeastOnce()).editMessageText(captor.capture());

        List<EditMessageText> edits = captor.getAllValues();
        // Last edit (onComplete final flush) must have the complete buffer
        EditMessageText last = edits.get(edits.size() - 1);
        assertEquals("Hello World", last.text());
        assertEquals(100L, last.chatId());
        assertEquals(42L, last.messageId());
    }

    /**
     * A mid-stream editMessageText failure must be swallowed. The stream must complete and
     * the final flush in onComplete must still fire with the full accumulated buffer.
     */
    @Test
    void midStreamEditFailureIsSwallowedAndStreamCompletes() {
        when(mockAssistant.streamChat(any(), any()))
            .thenReturn(Multi.createFrom().items("tok1", "tok2"));
        doThrow(new RuntimeException("429 rate limit"))
            .doNothing()
            .doNothing()
            .when(mockApi).editMessageText(any());

        // Must not throw
        handler.stream(100L, 42L, "sess", "hello");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        // tok1 (throws), tok2 (ok), onComplete (ok) = 3 total attempts
        verify(mockApi, times(3)).editMessageText(captor.capture());
        // Final edit (index 2) has the full buffer
        assertEquals("tok1tok2", captor.getAllValues().get(2).text());
    }

    /**
     * When the stream itself fails (auth error, timeout, etc.), the placeholder message
     * must be replaced with "Something went wrong." — not left as "…".
     */
    @Test
    void streamErrorReplacesMessageWithErrorText() {
        when(mockAssistant.streamChat(any(), any()))
            .thenReturn(Multi.createFrom().failure(new RuntimeException("auth error")));

        handler.stream(100L, 42L, "sess", "hello");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(mockApi, times(1)).editMessageText(captor.capture());
        assertEquals("Something went wrong.", captor.getValue().text());
    }

    /**
     * When accumulated tokens exceed 4096 chars, every editMessageText call must receive
     * clamped text of at most 4096 characters (matching Telegram's hard limit).
     */
    @Test
    void bufferIsClamped() {
        String bigToken = "x".repeat(5000);
        when(mockAssistant.streamChat(any(), any()))
            .thenReturn(Multi.createFrom().items(bigToken));

        handler.stream(100L, 42L, "sess", "hello");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(mockApi, atLeastOnce()).editMessageText(captor.capture());
        captor.getAllValues().forEach(e ->
            assertTrue(e.text().length() <= 4096,
                "edit text must be <= 4096 chars, got " + e.text().length()));
    }
}
