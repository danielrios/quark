package com.quark.core;

/**
 * Runtime lifecycle contract for a single agent turn, per ADR 0001
 * (docs/adr/0001-event-driven-agentevent-stream.md).
 *
 * <p>Every event carries a {@code turnId} for correlation across the stream. The runtime
 * guarantees exactly one terminal event per turn ({@link TurnCompleted} or {@link TurnFailed}),
 * after which the stream completes normally — failures are events, not {@code onError()}.
 *
 * <p>Renderers must project this hierarchy with {@code instanceof} checks, never an exhaustive
 * {@code switch}, so that new variants do not break existing renderers.
 */
public sealed interface AgentEvent {

    String turnId();

    /** Emitted when a turn begins execution. */
    record TurnStarted(String turnId, String sessionId) implements AgentEvent {}

    /** Emitted once conversation memory has been loaded for the turn. */
    record MemoryLoaded(String turnId, int messageCount) implements AgentEvent {}

    /** Emitted when the model provider has been invoked. */
    record ModelInvoked(String turnId) implements AgentEvent {}

    /** Emitted for each token produced by the model while streaming. */
    record TokenEmitted(String turnId, String text) implements AgentEvent {}

    /** Emitted when the model has finished producing tokens. */
    record ModelCompleted(String turnId) implements AgentEvent {}

    /** Terminal event: the turn completed successfully with the full response text. */
    record TurnCompleted(String turnId, String text) implements AgentEvent {}

    /** Terminal event: the turn failed with a human-readable reason. */
    record TurnFailed(String turnId, String reason) implements AgentEvent {}
}
