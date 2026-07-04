package com.quark.provider;

import com.quark.core.ChatMessage;
import io.smallrye.mutiny.Multi;
import java.util.List;

/**
 * Provider SPI (Plan 4, ADR 0001 — langchain4j stays inside {@code provider.*}
 * implementations).
 *
 * <p>Takes the fully built prompt (system + history + user message, oldest first)
 * and returns the raw token stream. {@code history} must be non-empty — callers
 * always pass the fully built prompt. Stream errors surface as {@link Multi}
 * failures; mapping failures to {@code AgentEvent.TurnFailed} is the runtime's
 * job — gateways never emit events.
 */
public interface ModelGateway {

    Multi<String> stream(List<ChatMessage> history);
}
