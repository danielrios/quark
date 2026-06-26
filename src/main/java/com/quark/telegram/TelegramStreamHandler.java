package com.quark.telegram;

import com.quark.chat.Assistant;
import com.quark.telegram.TelegramMessages.EditMessageText;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Streaming loop for Telegram: sends a placeholder message, then edits it with accumulated tokens
 * from the LLM, throttled to avoid Telegram's rate limit. Blocks the calling virtual thread via
 * CountDownLatch so the CDI request context in TelegramBotRunner.handle() stays active for the
 * full stream duration.
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
        long[] lastEdit = {System.currentTimeMillis()};

        assistant.streamChat(sessionId, userText)
            .subscribe().with(
                token -> {
                    buffer.append(token);
                    long now = System.currentTimeMillis();
                    if (now - lastEdit[0] >= throttleMs) {
                        tryEdit(chatId, messageId,
                            TelegramMessages.clampToTelegramLimit(buffer.toString()));
                        lastEdit[0] = now;
                    }
                },
                error -> {
                    Log.error("Stream error for session " + sessionId, error);
                    tryEdit(chatId, messageId, "Something went wrong.");
                    latch.countDown();
                },
                () -> {
                    tryEdit(chatId, messageId,
                        TelegramMessages.clampToTelegramLimit(buffer.toString()));
                    latch.countDown();
                });

        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                Log.warn("Stream timed out for session " + sessionId + " — flushing buffer");
                tryEdit(chatId, messageId, TelegramMessages.clampToTelegramLimit(buffer.toString()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void tryEdit(long chatId, long messageId, String text) {
        try {
            api.editMessageText(new EditMessageText(chatId, messageId, text));
        } catch (Exception e) {
            Log.warn("editMessageText failed (chatId=" + chatId + ", msgId=" + messageId + ")", e);
        }
    }
}
