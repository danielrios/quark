package com.quark.runtime;

import com.quark.core.AgentEvent;
import com.quark.core.ChatMessage;
import com.quark.core.TurnRequest;
import com.quark.memory.ChatMemoryStore;
import com.quark.provider.ModelGateway;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The single orchestration point for an agent turn (ADR 0001).
 *
 * <p>{@link #execute(TurnRequest)} loads conversation history, emits {@link
 * AgentEvent.TurnStarted}, {@link AgentEvent.MemoryLoaded}, and {@link AgentEvent.ModelInvoked},
 * then streams the {@link ModelGateway} token-by-token as {@link AgentEvent.TokenEmitted} while
 * accumulating the full response text. On success, both the user and assistant messages are
 * persisted to the {@link ChatMemoryStore} and the stream ends with {@link
 * AgentEvent.ModelCompleted} followed by the terminal {@link AgentEvent.TurnCompleted}.
 *
 * <p>Any failure — before or during model invocation — becomes exactly one terminal {@link
 * AgentEvent.TurnFailed} event, and the {@link Multi} completes normally afterward; failures are
 * events, not {@code onError()}. Nothing is persisted on a failed or cancelled turn — a
 * deliberate deviation from the retired AI-service behavior (ADR 0007). One caveat: the two
 * persistence appends are not atomic, so a store whose ASSISTANT append throws can leave an
 * orphan USER message behind (unreachable with {@code InMemoryChatMemoryStore}).
 *
 * <p>{@link #execute(TurnRequest)} returns a cold {@link Multi}: each subscription re-runs the
 * whole turn, and all per-turn state (the turn id, the accumulator) lives inside the {@code
 * deferred} supplier, so concurrent subscriptions never share state.
 *
 * <p>At a saturated session the prompt carries the full stored window <em>plus</em> the pending
 * user message (window+1 conversation messages) — intended: the store bounds persisted history,
 * eviction catches up on the post-turn appends. (Plan 2's MessageWindowChatMemory counted the
 * pending message inside the window; the one-message difference is accepted.)
 */
@ApplicationScoped
public class AgentRuntime {

    static final String SYSTEM_PROMPT =
            "You are quark, a concise and helpful assistant. Answer in plain text.";

    private final ChatMemoryStore memory;
    private final ModelGateway gateway;

    @Inject
    public AgentRuntime(ChatMemoryStore memory, ModelGateway gateway) {
        this.memory = memory;
        this.gateway = gateway;
    }

    public Multi<AgentEvent> execute(TurnRequest request) {
        return Multi.createFrom().deferred(() -> {
            String turnId = UUID.randomUUID().toString();
            String sessionId = request.sessionId();
            Log.info("turn " + turnId + " started (session " + sessionId + ")");
            try {
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
                        // Degenerate zero-token completion: renderers show the error fallback,
                        // so memory must agree the turn did not happen — persisting the pair
                        // would replay an empty assistant message and double a resent question.
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
     * Drops all conversation memory for {@code sessionId} — the {@code /reset} path. Lives on
     * the runtime because adapters may not touch chat memory directly (ADR 0002 boundaries).
     */
    public void reset(String sessionId) {
        memory.delete(sessionId);
    }

    /**
     * Human-readable failure reason for {@link AgentEvent.TurnFailed}. Message-only — exception
     * class names never enter the event stream, which Plan 6 will serialize to external SSE
     * clients. The full stack trace stays in the log beside the {@code turnId}.
     */
    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        return (message == null || message.isBlank())
                ? failure.getClass().getSimpleName()
                : message;
    }
}
