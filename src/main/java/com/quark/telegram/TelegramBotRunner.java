package com.quark.telegram;

import com.quark.core.AgentEvent;
import com.quark.core.TurnRequest;
import com.quark.runtime.AgentRuntime;
import com.quark.telegram.TelegramMessages.GetUpdatesResponse;
import com.quark.telegram.TelegramMessages.IncomingText;
import com.quark.telegram.TelegramMessages.SendMessage;
import com.quark.telegram.TelegramMessages.SendMessageResponse;
import com.quark.telegram.TelegramMessages.TelegramUpdate;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class TelegramBotRunner {

    @Inject
    @RestClient
    TelegramApi api;

    @Inject
    AgentRuntime runtime;

    @Inject
    TelegramStreamHandler streamHandler;

    @ConfigProperty(name = "quark.telegram.enabled")
    boolean enabled;

    @ConfigProperty(name = "quark.telegram.bot-token")
    String botToken;

    @ConfigProperty(name = "quark.telegram.poll-timeout-seconds", defaultValue = "30")
    int pollTimeoutSeconds;

    private volatile boolean running = true;

    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            Log.info("Telegram disabled (quark.telegram.enabled=false); poller not started");
            return;
        }
        if (botToken == null || botToken.isBlank()) {
            Log.warn("Telegram enabled but no bot token set; poller not started");
            return;
        }
        Thread.ofVirtual().name("telegram-poll").start(this::pollLoop);
        Log.info("Telegram poller started");
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
    }

    void pollLoop() {
        long offset = 0;
        while (running) {
            try {
                GetUpdatesResponse resp = api.getUpdates(offset, pollTimeoutSeconds);
                List<TelegramUpdate> updates =
                        (resp == null || resp.result() == null) ? List.of() : resp.result();
                for (TelegramUpdate u : updates) {
                    handle(u);
                }
                offset = TelegramMessages.nextOffset(offset, updates);
            } catch (Exception e) {
                Log.error("Telegram poll iteration failed", e);
                sleepQuietly(1000);
            }
        }
    }

    private void handle(TelegramUpdate update) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            IncomingText incoming = TelegramMessages.extractText(update).orElse(null);
            if (incoming == null) {
                return;
            }
            String sessionId = String.valueOf(incoming.chatId());
            long chatId = incoming.chatId();

            if (TelegramCommands.parse(incoming.text()) == TelegramCommands.Command.RESET) {
                String reply = dispatch(sessionId, incoming.text());
                api.sendMessage(new SendMessage(chatId, TelegramMessages.clampToTelegramLimit(reply)));
            } else {
                SendMessageResponse placeholder = api.sendMessage(new SendMessage(chatId, "⏳ Thinking..."));
                if (!placeholder.ok() || placeholder.result() == null) {
                    Log.warn("Failed to send placeholder for chatId=" + chatId);
                    return;
                }
                streamHandler.stream(chatId, placeholder.result().messageId(), sessionId, incoming.text());
            }
        } catch (Exception e) {
            Log.error("Failed to handle update " + update.updateId(), e);
        } finally {
            requestContext.terminate();
        }
    }

    // Package-private: test seam used by TelegramBotRunnerResetTest and TelegramConversationMemoryTest.
    // Handles commands and blocking chat. The live CHAT path uses handle() → TelegramStreamHandler;
    // dispatch RESET is also the live reset path.
    String dispatch(String sessionId, String text) {
        switch (TelegramCommands.parse(text)) {
            case RESET:
                runtime.reset(sessionId);
                return "Memory cleared. Starting fresh.";
            case CHAT:
            default:
                return chat(sessionId, text);
        }
    }

    private String chat(String sessionId, String userMessage) {
        try {
            AgentEvent last = runtime.execute(TurnRequest.of(sessionId, userMessage))
                    .collect().last().await().atMost(Duration.ofSeconds(60));
            return (last instanceof AgentEvent.TurnCompleted completed)
                    ? completed.text()
                    : TelegramMessages.ERR_FALLBACK;
        } catch (Exception e) {
            Log.error("Turn failed for session " + sessionId, e);
            return TelegramMessages.ERR_FALLBACK;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
