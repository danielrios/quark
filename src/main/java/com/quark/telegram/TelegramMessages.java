package com.quark.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

public final class TelegramMessages {

    private TelegramMessages() {}

    public record GetUpdatesResponse(boolean ok, List<TelegramUpdate> result) {}

    public record TelegramUpdate(@JsonProperty("update_id") long updateId, Message message) {}

    public record Message(Chat chat, String text) {}

    public record Chat(long id) {}

    public record SendMessage(@JsonProperty("chat_id") long chatId, String text) {}

    public record IncomingText(long chatId, String text) {}

    public static Optional<IncomingText> extractText(TelegramUpdate update) {
        Message m = update.message();
        if (m == null || m.chat() == null || m.text() == null || m.text().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new IncomingText(m.chat().id(), m.text()));
    }

    public static long nextOffset(long current, List<TelegramUpdate> updates) {
        long max = current - 1;
        for (TelegramUpdate u : updates) {
            max = Math.max(max, u.updateId());
        }
        return max + 1;
    }
}
