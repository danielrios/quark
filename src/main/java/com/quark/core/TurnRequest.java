package com.quark.core;

import java.util.Optional;

/**
 * One turn of input handed from an adapter to {@code AgentRuntime.execute}.
 *
 * <p>{@code provider} selects a model gateway and stays empty until Plan 5
 * introduces provider preference; the {@code (sessionId, provider?, message)}
 * shape is fixed by the Destination pipeline in {@code ARCHITECTURE.md}.
 */
public record TurnRequest(String sessionId, Optional<String> provider, String message) {

    /**
     * Creates a {@link TurnRequest} with no provider preference.
     */
    public static TurnRequest of(String sessionId, String message) {
        return new TurnRequest(sessionId, Optional.empty(), message);
    }
}
