package com.quark.core;

/**
 * The core-owned conversation message shape.
 *
 * <p>Providers (e.g. {@code provider.gemini}) map this to their own SDK
 * types at the boundary, so langchain4j types never leak into core,
 * runtime, memory, or adapters (see ADR 0002 boundaries).
 */
public record ChatMessage(Role role, String text) {

    /**
     * Who authored a {@link ChatMessage}.
     */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
