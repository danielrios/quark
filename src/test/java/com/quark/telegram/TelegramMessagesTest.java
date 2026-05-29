package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.telegram.TelegramMessages.Chat;
import com.quark.telegram.TelegramMessages.IncomingText;
import com.quark.telegram.TelegramMessages.Message;
import com.quark.telegram.TelegramMessages.TelegramUpdate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TelegramMessagesTest {

    @Test
    void extractsChatIdAndTextFromTextMessage() {
        var update = new TelegramUpdate(42, new Message(new Chat(7), "hello"));
        Optional<IncomingText> result = TelegramMessages.extractText(update);
        assertTrue(result.isPresent());
        assertEquals(7, result.get().chatId());
        assertEquals("hello", result.get().text());
    }

    @Test
    void ignoresUpdateWithoutMessage() {
        var update = new TelegramUpdate(42, null);
        assertTrue(TelegramMessages.extractText(update).isEmpty());
    }

    @Test
    void ignoresMessageWithoutText() {
        var update = new TelegramUpdate(42, new Message(new Chat(7), null));
        assertTrue(TelegramMessages.extractText(update).isEmpty());
    }

    @Test
    void ignoresBlankText() {
        var update = new TelegramUpdate(42, new Message(new Chat(7), "   "));
        assertTrue(TelegramMessages.extractText(update).isEmpty());
    }

    @Test
    void nextOffsetIsHighestUpdateIdPlusOne() {
        var updates = List.of(
                new TelegramUpdate(10, null),
                new TelegramUpdate(12, null),
                new TelegramUpdate(11, null));
        assertEquals(13, TelegramMessages.nextOffset(0, updates));
    }

    @Test
    void nextOffsetUnchangedForEmptyBatch() {
        assertEquals(5, TelegramMessages.nextOffset(5, List.of()));
    }
}
