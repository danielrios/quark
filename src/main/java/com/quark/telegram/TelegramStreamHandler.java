package com.quark.telegram;

import com.quark.chat.Assistant;
import com.quark.telegram.TelegramMessages.EditMessageText;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Streaming loop for Telegram: subscribes to an LLM token stream and edits a pre-sent placeholder
 * message as tokens arrive, throttled to avoid Telegram's rate limit. Blocks the calling virtual
 * thread via CountDownLatch so the CDI request context in TelegramBotRunner.handle() stays active
 * for the full stream duration.
 *
 * <p>buffer and lastEdit are method-local — this bean is @ApplicationScoped (singleton) and
 * stream() may be called concurrently for different users.
 */
@ApplicationScoped
public class TelegramStreamHandler {

    @Inject
    @RestClient
    TelegramApi api;

    @Inject
    Assistant assistant;

    @ConfigProperty(name = "quark.telegram.stream-throttle-ms", defaultValue = "750")
    long throttleMs;

    public void stream(long chatId, long messageId, String sessionId, String userText) {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder buffer = new StringBuilder();
        AtomicLong lastEdit = new AtomicLong(System.currentTimeMillis());

        assistant.streamChat(sessionId, userText)
            .subscribe().with(
                token -> {
                    buffer.append(token);
                    long now = System.currentTimeMillis();
                    if (now - lastEdit.get() >= throttleMs) {
                        flushBuffer(chatId, messageId, buffer);
                        lastEdit.set(now);
                    }
                },
                error -> {
                    Log.error("Stream error for session " + sessionId, error);
                    tryEdit(chatId, messageId, TelegramMessages.ERR_FALLBACK);
                    latch.countDown();
                },
                () -> {
                    flushBuffer(chatId, messageId, buffer);
                    latch.countDown();
                });

        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                Log.warn("Stream timed out for session " + sessionId + " — flushing buffer");
                flushBuffer(chatId, messageId, buffer);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void flushBuffer(long chatId, long messageId, StringBuilder buffer) {
        tryEdit(chatId, messageId, TelegramMessages.clampToTelegramLimit(buffer));
    }

    private void tryEdit(long chatId, long messageId, String text) {
        try {
            api.editMessageText(new EditMessageText(chatId, messageId, text));
        } catch (Exception e) {
            Log.warn("editMessageText failed (chatId=" + chatId + ", msgId=" + messageId + ")", e);
        }
    }
}
