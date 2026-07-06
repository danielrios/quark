package com.quark.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.adapter.telegram.TelegramMessages.Chat;
import com.quark.adapter.telegram.TelegramMessages.IncomingText;
import com.quark.adapter.telegram.TelegramMessages.Message;
import com.quark.adapter.telegram.TelegramMessages.TelegramUpdate;
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

    @Test
    void clampLeavesShortTextUnchanged() {
        assertEquals("hello", TelegramMessages.clampToTelegramLimit("hello"));
    }

    @Test
    void clampLeavesTextAtLimitUnchanged() {
        String atLimit = "x".repeat(TelegramMessages.MAX_MESSAGE_LENGTH);
        assertEquals(atLimit, TelegramMessages.clampToTelegramLimit(atLimit));
    }

    @Test
    void clampTruncatesOverlongTextToLimitWithEllipsis() {
        String overlong = "x".repeat(TelegramMessages.MAX_MESSAGE_LENGTH + 500);
        String clamped = TelegramMessages.clampToTelegramLimit(overlong);
        assertEquals(TelegramMessages.MAX_MESSAGE_LENGTH, clamped.length());
        assertTrue(clamped.endsWith("…"));
    }
}
