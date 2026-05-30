package com.quark.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quark.telegram.TelegramCommands.Command;
import org.junit.jupiter.api.Test;

class TelegramCommandsTest {

    @Test
    void plainResetIsResetCommand() {
        assertEquals(Command.RESET, TelegramCommands.parse("/reset"));
    }

    @Test
    void resetWithBotMentionIsResetCommand() {
        assertEquals(Command.RESET, TelegramCommands.parse("/reset@quark_bot"));
    }

    @Test
    void resetWithTrailingArgsIsResetCommand() {
        assertEquals(Command.RESET, TelegramCommands.parse("/reset please"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals(Command.RESET, TelegramCommands.parse("   /reset  "));
    }

    @Test
    void resetIsCaseInsensitive() {
        assertEquals(Command.RESET, TelegramCommands.parse("/RESET"));
    }

    @Test
    void ordinaryTextIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse("hello there"));
    }

    @Test
    void unhandledSlashCommandIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse("/start"));
    }

    @Test
    void resetPrefixButLongerWordIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse("/resets"));
    }

    @Test
    void nullTextIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse(null));
    }

    @Test
    void blankTextIsChat() {
        assertEquals(Command.CHAT, TelegramCommands.parse("   "));
    }
}
