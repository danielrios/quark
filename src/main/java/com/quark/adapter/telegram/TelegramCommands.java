package com.quark.adapter.telegram;

public final class TelegramCommands {

    private TelegramCommands() {}

    public enum Command {
        RESET,
        CHAT
    }

    public static Command parse(String text) {
        if (text == null) {
            return Command.CHAT;
        }
        String first = text.strip().split("\\s+", 2)[0];
        int at = first.indexOf('@');
        if (at >= 0) {
            first = first.substring(0, at);
        }
        if (first.equalsIgnoreCase("/reset")) {
            return Command.RESET;
        }
        return Command.CHAT;
    }
}
