package com.quark.adapter.telegram;

import com.quark.core.AgentEvent;
import com.quark.core.TurnRequest;
import com.quark.runtime.AgentRuntime;
import com.quark.adapter.telegram.TelegramMessages.EditMessageText;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Streaming loop for Telegram: subscribes to the {@link AgentRuntime}'s {@code Multi<AgentEvent>}
 * for a turn and edits a pre-sent placeholder message as tokens arrive, throttled to avoid
 * Telegram's rate limit. The item callback projects the event stream with {@code instanceof}
 * checks — never an exhaustive {@code switch} — so new event variants (ADR 0001) do not break
 * this renderer. Blocks the calling virtual thread via CountDownLatch so the CDI request context
 * in TelegramBotRunner.handle() stays active for the full stream duration.
 *
 * <p>buffer and lastEdit are method-local — this bean is @ApplicationScoped (singleton) and
 * stream() may be called concurrently for different users.
 *
 * <p>Callbacks execute on Mutiny's emitter thread; that thread does not carry the CDI request
 * context. All injected beans used inside callbacks (TelegramApi, the latch, buffer) must be
 * app-scoped or method-local — do not introduce request-scoped dependencies in callbacks.
 */
@ApplicationScoped
public class TelegramStreamHandler {

    @Inject
    @RestClient
    TelegramApi api;

    @Inject
    AgentRuntime runtime;

    @ConfigProperty(name = "quark.telegram.stream-throttle-ms", defaultValue = "750")
    long throttleMs;

    public void stream(long chatId, long messageId, String sessionId, String userText) {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder buffer = new StringBuilder();
        AtomicLong lastEdit = new AtomicLong(System.currentTimeMillis());

        Cancellable subscription = runtime.execute(TurnRequest.of(sessionId, userText))
            .subscribe().with(
                event -> {
                    if (event instanceof AgentEvent.TokenEmitted token) {
                        buffer.append(token.text());
                        long now = System.currentTimeMillis();
                        if (now - lastEdit.get() >= throttleMs) {
                            flushBuffer(chatId, messageId, buffer);
                            lastEdit.set(now);
                        }
                    } else if (event instanceof AgentEvent.TurnFailed) {
                        tryEdit(chatId, messageId, TelegramMessages.ERR_FALLBACK);
                    } else if (event instanceof AgentEvent.TurnCompleted completed) {
                        if (completed.text().isBlank()) {
                            // Blank turn (zero tokens OR whitespace-only — a real Gemini stop
                            // mode): Telegram rejects empty-text edits, which would strand the
                            // placeholder on "Thinking..." — surface the fallback. Same
                            // isBlank() predicate as the runtime's persistence skip (ADR 0007),
                            // so memory and rendering agree the turn did not happen.
                            tryEdit(chatId, messageId, TelegramMessages.ERR_FALLBACK);
                        } else {
                            flushBuffer(chatId, messageId, buffer);
                        }
                    }
                },
                error -> {
                    // Defensive only: AgentRuntime.execute() maps failures to TurnFailed
                    // events and completes normally rather than calling onError.
                    Log.error("Stream error for session " + sessionId, error);
                    tryEdit(chatId, messageId, TelegramMessages.ERR_FALLBACK);
                    latch.countDown();
                },
                () -> {
                    latch.countDown();
                });

        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                Log.warn("Stream timed out for session " + sessionId + " — cancelling subscription");
                subscription.cancel();
                tryEdit(chatId, messageId, TelegramMessages.ERR_FALLBACK);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void flushBuffer(long chatId, long messageId, StringBuilder buffer) {
        String text = TelegramMessages.clampToTelegramLimit(buffer);
        if (text.isBlank()) {
            // Telegram 400s on empty-text edits; a blank mid-stream buffer just waits for
            // real tokens (or for the TurnCompleted fallback).
            return;
        }
        tryEdit(chatId, messageId, text);
    }

    private void tryEdit(long chatId, long messageId, String text) {
        try {
            api.editMessageText(new EditMessageText(chatId, messageId, text));
        } catch (Exception e) {
            Log.warn("editMessageText failed (chatId=" + chatId + ", msgId=" + messageId + ")", e);
        }
    }
}
