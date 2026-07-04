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

import com.quark.core.AgentEvent;
import com.quark.runtime.AgentRuntime;
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
    AgentRuntime mockRuntime;

    @InjectMock
    @RestClient
    TelegramApi mockApi;

    /**
     * With throttleMs=0, every TokenEmitted event triggers an edit. The final flush on
     * TurnCompleted also fires. The buffer must accumulate across all tokens so the last edit
     * has the complete text.
     */
    @Test
    void tokensAccumulateAndFinalFlushHasCompleteBuffer() {
        when(mockRuntime.execute(any())).thenReturn(Multi.createFrom().iterable(java.util.List.<AgentEvent>of(
            new AgentEvent.TokenEmitted("t1", "Hello"),
            new AgentEvent.TokenEmitted("t1", " "),
            new AgentEvent.TokenEmitted("t1", "World"),
            new AgentEvent.ModelCompleted("t1"),
            new AgentEvent.TurnCompleted("t1", "Hello World"))));

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
     * A mid-stream editMessageText failure must be swallowed. The event stream must complete and
     * the final flush on TurnCompleted must still fire with the full accumulated buffer.
     */
    @Test
    void midStreamEditFailureIsSwallowedAndStreamCompletes() {
        when(mockRuntime.execute(any())).thenReturn(Multi.createFrom().iterable(java.util.List.<AgentEvent>of(
            new AgentEvent.TokenEmitted("t1", "tok1"),
            new AgentEvent.TokenEmitted("t1", "tok2"),
            new AgentEvent.ModelCompleted("t1"),
            new AgentEvent.TurnCompleted("t1", "tok1tok2"))));
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
     * When the turn fails (auth error, timeout, etc.), it arrives as a single TurnFailed event
     * — not a Multi failure — and the placeholder message must be replaced with
     * "Something went wrong." — not left as "…".
     */
    @Test
    void streamErrorReplacesMessageWithErrorText() {
        when(mockRuntime.execute(any())).thenReturn(Multi.createFrom().iterable(java.util.List.<AgentEvent>of(
            new AgentEvent.TurnFailed("t1", "auth error"))));

        handler.stream(100L, 42L, "sess", "hello");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(mockApi, times(1)).editMessageText(captor.capture());
        assertEquals(TelegramMessages.ERR_FALLBACK, captor.getValue().text());
    }

    /**
     * When accumulated tokens exceed 4096 chars, every editMessageText call must receive
     * clamped text of at most 4096 characters (matching Telegram's hard limit).
     */
    @Test
    void bufferIsClamped() {
        String bigToken = "x".repeat(5000);
        when(mockRuntime.execute(any())).thenReturn(Multi.createFrom().iterable(java.util.List.<AgentEvent>of(
            new AgentEvent.TokenEmitted("t1", bigToken),
            new AgentEvent.ModelCompleted("t1"),
            new AgentEvent.TurnCompleted("t1", bigToken))));

        handler.stream(100L, 42L, "sess", "hello");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(mockApi, atLeastOnce()).editMessageText(captor.capture());
        captor.getAllValues().forEach(e ->
            assertTrue(e.text().length() <= 4096,
                "edit text must be <= 4096 chars, got " + e.text().length()));
    }
}
