package com.quark.runtime;

import com.quark.core.AgentEvent;
import com.quark.core.ChatMessage;
import com.quark.core.TurnRequest;
import com.quark.memory.ChatMemoryStore;
import com.quark.memory.preference.ModelPreference;
import com.quark.memory.preference.ProviderPreferenceStore;
import com.quark.provider.ModelGateway;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The single orchestration point for an agent turn (ADR 0001).
 *
 * <p>Plan 5: gateway resolved per turn via the session preference chain:
 * {@code ProviderPreferenceStore.get(sessionId)} → {@code TurnRequest.provider()} →
 * {@code quark.provider.default} config. Unknown provider names emit {@link
 * AgentEvent.TurnFailed} — no auto-fallback (spec §Error handling).
 *
 * <p>{@link #reset(String)} clears conversation memory only; it does NOT clear the
 * provider preference — "/reset means forget history, not factory reset".
 */
@ApplicationScoped
public class AgentRuntime {

    static final String SYSTEM_PROMPT =
            "You are quark, a concise and helpful assistant. Answer in plain text.";

    private final ChatMemoryStore memory;
    private final ProviderPreferenceStore preference;
    private final Instance<ModelGateway> gateways;
    private final String defaultProvider;

    @Inject
    public AgentRuntime(
            ChatMemoryStore memory,
            ProviderPreferenceStore preference,
            @Any Instance<ModelGateway> gateways,
            @ConfigProperty(name = "quark.provider.default") String defaultProvider) {
        this.memory = memory;
        this.preference = preference;
        this.gateways = gateways;
        this.defaultProvider = defaultProvider;
    }

    public Multi<AgentEvent> execute(TurnRequest request) {
        return Multi.createFrom().deferred(() -> {
            String turnId = UUID.randomUUID().toString();
            String sessionId = request.sessionId();
            Log.info("turn " + turnId + " started (session " + sessionId + ")");
            try {
                // Resolve provider: session preference > request override > global default
                String providerName = preference.get(sessionId)
                        .map(ModelPreference::provider)
                        .or(request::provider)
                        .orElse(defaultProvider);

                Instance<ModelGateway> selected = gateways.select(namedLiteral(providerName));

                if (!selected.isResolvable()) {
                    Log.warn("turn " + turnId + " unknown provider: " + providerName);
                    return Multi.createFrom().items(
                            new AgentEvent.TurnStarted(turnId, sessionId),
                            new AgentEvent.TurnFailed(turnId, "unknown provider: " + providerName));
                }

                ModelGateway gateway = selected.get();

                List<ChatMessage> history = memory.load(sessionId);

                List<ChatMessage> prompt = new ArrayList<>();
                prompt.add(new ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM_PROMPT));
                prompt.addAll(history);
                ChatMessage userMessage = new ChatMessage(ChatMessage.Role.USER, request.message());
                prompt.add(userMessage);

                StringBuilder accumulated = new StringBuilder();

                Multi<AgentEvent> head = Multi.createFrom().items(
                        new AgentEvent.TurnStarted(turnId, sessionId),
                        new AgentEvent.MemoryLoaded(turnId, history.size()),
                        new AgentEvent.ModelInvoked(turnId));

                Multi<AgentEvent> tokens = gateway.stream(List.copyOf(prompt))
                        .onItem().invoke(accumulated::append)
                        .onItem().transform(chunk -> (AgentEvent) new AgentEvent.TokenEmitted(turnId, chunk));

                Multi<AgentEvent> tail = Multi.createFrom().deferred(() -> {
                    String text = accumulated.toString();
                    if (text.isBlank()) {
                        Log.warn("turn " + turnId + " completed blank — nothing persisted");
                    } else {
                        memory.append(sessionId, userMessage);
                        memory.append(sessionId, new ChatMessage(ChatMessage.Role.ASSISTANT, text));
                    }
                    Log.info("turn " + turnId + " completed (" + text.length() + " chars)");
                    return Multi.createFrom().items(
                            new AgentEvent.ModelCompleted(turnId),
                            new AgentEvent.TurnCompleted(turnId, text));
                });

                return Multi.createBy().concatenating().streams(head, tokens, tail)
                        .onFailure().recoverWithItem(failure -> {
                            Log.error("turn " + turnId + " failed", failure);
                            return (AgentEvent) new AgentEvent.TurnFailed(turnId, reason(failure));
                        });
            } catch (Exception e) {
                Log.error("turn " + turnId + " failed before model invocation", e);
                return Multi.createFrom().items(
                        new AgentEvent.TurnStarted(turnId, sessionId),
                        new AgentEvent.TurnFailed(turnId, reason(e)));
            }
        });
    }

    /**
     * Drops conversation memory for {@code sessionId} — the {@code /reset} path.
     * Does NOT clear provider preference (spec: /reset = forget history, not factory reset).
     */
    public void reset(String sessionId) {
        memory.delete(sessionId);
    }

    private static Annotation namedLiteral(String name) {
        return new Named() {
            @Override public String value() { return name; }
            @Override public Class<? extends Annotation> annotationType() { return Named.class; }
        };
    }

    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        return (message == null || message.isBlank()) ? "internal error" : message;
    }
}
