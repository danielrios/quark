package com.quark.adapter.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

public final class TelegramMessages {

    private TelegramMessages() {}

    /** Telegram rejects {@code sendMessage} payloads whose text exceeds 4096 characters. */
    public static final int MAX_MESSAGE_LENGTH = 4096;

    public static final String ERR_FALLBACK = "Something went wrong.";

    public record GetUpdatesResponse(boolean ok, List<TelegramUpdate> result) {}

    public record TelegramUpdate(@JsonProperty("update_id") long updateId, Message message) {}

    public record Message(Chat chat, String text) {}

    public record Chat(long id) {}

    public record SendMessage(@JsonProperty("chat_id") long chatId, String text) {}

    public record SendMessageResponse(boolean ok, MessageResult result) {}

    public record MessageResult(@JsonProperty("message_id") long messageId) {}

    public record EditMessageText(
        @JsonProperty("chat_id") long chatId,
        @JsonProperty("message_id") long messageId,
        String text) {}

    public record IncomingText(long chatId, String text) {}

    public static Optional<IncomingText> extractText(TelegramUpdate update) {
        Message m = update.message();
        if (m == null || m.chat() == null || m.text() == null || m.text().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new IncomingText(m.chat().id(), m.text()));
    }

    /**
     * Clamps reply text to Telegram's per-message limit. Over-limit text is truncated and marked
     * with a trailing ellipsis so the user sees a (cut) answer rather than nothing. Full chunking
     * of long replies is deferred to Plan 3.
     */
    public static String clampToTelegramLimit(String text) {
        if (text.length() <= MAX_MESSAGE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    public static String clampToTelegramLimit(StringBuilder buffer) {
        if (buffer.length() <= MAX_MESSAGE_LENGTH) {
            return buffer.toString();
        }
        return buffer.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    public static long nextOffset(long current, List<TelegramUpdate> updates) {
        long max = current - 1;
        for (TelegramUpdate u : updates) {
            max = Math.max(max, u.updateId());
        }
        return max + 1;
    }
}
