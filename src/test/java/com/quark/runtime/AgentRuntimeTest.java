package com.quark.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quark.core.AgentEvent;
import com.quark.core.ChatMessage;
import com.quark.core.TurnRequest;
import com.quark.memory.ChatMemoryStore;
import com.quark.provider.ModelGateway;
import io.smallrye.mutiny.Multi;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the ADR 0001 runtime contract with fakes — event order, turnId propagation,
 * persist-on-success-only, failures-as-events.
 */
class AgentRuntimeTest {

    private static final class FakeGateway implements ModelGateway {

        List<ChatMessage> receivedPrompt;
        private final Multi<String> toReturn;

        FakeGateway(Multi<String> toReturn) {
            this.toReturn = toReturn;
        }

        @Override
        public Multi<String> stream(List<ChatMessage> history) {
            this.receivedPrompt = history;
            return toReturn;
        }
    }

    private static final class FakeStore implements ChatMemoryStore {

        final Map<String, List<ChatMessage>> sessions = new HashMap<>();
        final List<ChatMessage> appended = new ArrayList<>();
        final List<String> appendedSessionIds = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean loadThrows = false;
        boolean appendThrows = false;

        @Override
        public List<ChatMessage> load(String sessionId) {
            if (loadThrows) {
                throw new IllegalStateException("store down");
            }
            return sessions.getOrDefault(sessionId, List.of());
        }

        @Override
        public void append(String sessionId, ChatMessage message) {
            if (appendThrows) {
                throw new IllegalStateException("append down");
            }
            appendedSessionIds.add(sessionId);
            appended.add(message);
            // Copy-put rather than computeIfAbsent+add: tests seed sessions with
            // immutable List.of, which computeIfAbsent would hand straight to add().
            List<ChatMessage> next = new ArrayList<>(sessions.getOrDefault(sessionId, List.of()));
            next.add(message);
            sessions.put(sessionId, next);
        }

        @Override
        public void delete(String sessionId) {
            deleted.add(sessionId);
        }
    }

    @Test
    void happyPathEmitsFullEventSequence() {
        var store = new FakeStore();
        store.sessions.put(
                "s1",
                List.of(
                        new ChatMessage(ChatMessage.Role.USER, "old-q"),
                        new ChatMessage(ChatMessage.Role.ASSISTANT, "old-a")));
        var gateway = new FakeGateway(Multi.createFrom().items("Hel", "lo"));
        var runtime = new AgentRuntime(store, gateway);

        List<AgentEvent> events =
                runtime.execute(TurnRequest.of("s1", "hi"))
                        .collect()
                        .asList()
                        .await()
                        .atMost(Duration.ofSeconds(5));

        assertEquals(7, events.size());

        var started = assertInstanceOf(AgentEvent.TurnStarted.class, events.get(0));
        assertEquals("s1", started.sessionId());

        var memoryLoaded = assertInstanceOf(AgentEvent.MemoryLoaded.class, events.get(1));
        assertEquals(2, memoryLoaded.messageCount());

        assertInstanceOf(AgentEvent.ModelInvoked.class, events.get(2));

        var firstToken = assertInstanceOf(AgentEvent.TokenEmitted.class, events.get(3));
        assertEquals("Hel", firstToken.text());
        var secondToken = assertInstanceOf(AgentEvent.TokenEmitted.class, events.get(4));
        assertEquals("lo", secondToken.text());

        assertInstanceOf(AgentEvent.ModelCompleted.class, events.get(5));

        var completed = assertInstanceOf(AgentEvent.TurnCompleted.class, events.get(6));
        assertEquals("Hello", completed.text());

        long terminalCount =
                events.stream()
                        .filter(
                                e ->
                                        e instanceof AgentEvent.TurnCompleted
                                                || e instanceof AgentEvent.TurnFailed)
                        .count();
        assertEquals(1, terminalCount);
    }

    @Test
    void everyEventCarriesTheSameNonBlankTurnId() {
        var store = new FakeStore();
        var gateway = new FakeGateway(Multi.createFrom().items("Hel", "lo"));
        var runtime = new AgentRuntime(store, gateway);

        List<AgentEvent> firstRun =
                runtime.execute(TurnRequest.of("s1", "hi"))
                        .collect()
                        .asList()
                        .await()
                        .atMost(Duration.ofSeconds(5));

        List<String> turnIds = firstRun.stream().map(AgentEvent::turnId).distinct().toList();
        assertEquals(1, turnIds.size());
        assertFalse(turnIds.get(0).isBlank());

        List<AgentEvent> secondRun =
                runtime.execute(TurnRequest.of("s1", "hi again"))
                        .collect()
                        .asList()
                        .await()
                        .atMost(Duration.ofSeconds(5));

        assertNotEquals(turnIds.get(0), secondRun.get(0).turnId());
    }

    @Test
    void gatewayReceivesSystemPlusHistoryPlusUserMessage() {
        var store = new FakeStore();
        store.sessions.put(
                "s1",
                List.of(
                        new ChatMessage(ChatMessage.Role.USER, "old-q"),
                        new ChatMessage(ChatMessage.Role.ASSISTANT, "old-a")));
        var gateway = new FakeGateway(Multi.createFrom().items("Hel", "lo"));
        var runtime = new AgentRuntime(store, gateway);

        runtime.execute(TurnRequest.of("s1", "hi"))
                .collect()
                .asList()
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals(
                "You are quark, a concise and helpful assistant. Answer in plain text.",
                AgentRuntime.SYSTEM_PROMPT);

        List<ChatMessage> prompt = gateway.receivedPrompt;
        assertEquals(4, prompt.size());
        assertEquals(ChatMessage.Role.SYSTEM, prompt.get(0).role());
        assertEquals(AgentRuntime.SYSTEM_PROMPT, prompt.get(0).text());
        assertEquals(ChatMessage.Role.USER, prompt.get(1).role());
        assertEquals("old-q", prompt.get(1).text());
        assertEquals(ChatMessage.Role.ASSISTANT, prompt.get(2).role());
        assertEquals("old-a", prompt.get(2).text());
        assertEquals(ChatMessage.Role.USER, prompt.get(3).role());
        assertEquals("hi", prompt.get(3).text());
    }

    @Test
    void successfulTurnPersistsUserThenAssistant() {
        var store = new FakeStore();
        var gateway = new FakeGateway(Multi.createFrom().items("Hel", "lo"));
        var runtime = new AgentRuntime(store, gateway);

        runtime.execute(TurnRequest.of("s1", "hi"))
                .collect()
                .asList()
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals(List.of("s1", "s1"), store.appendedSessionIds);
        assertEquals(2, store.appended.size());
        assertEquals(ChatMessage.Role.USER, store.appended.get(0).role());
        assertEquals("hi", store.appended.get(0).text());
        assertEquals(ChatMessage.Role.ASSISTANT, store.appended.get(1).role());
        assertEquals("Hello", store.appended.get(1).text());
    }

    @Test
    void gatewayFailureEmitsSingleTurnFailedAndPersistsNothing() {
        var store = new FakeStore();
        var gateway =
                new FakeGateway(
                        Multi.createBy()
                                .concatenating()
                                .streams(
                                        Multi.createFrom().items("He"),
                                        Multi.createFrom().failure(new RuntimeException("boom"))));
        var runtime = new AgentRuntime(store, gateway);

        List<AgentEvent> events =
                runtime.execute(TurnRequest.of("s1", "hi"))
                        .collect()
                        .asList()
                        .await()
                        .atMost(Duration.ofSeconds(5));

        var failed = assertInstanceOf(AgentEvent.TurnFailed.class, events.get(events.size() - 1));
        assertTrue(failed.reason().contains("boom"));

        long failedCount = events.stream().filter(e -> e instanceof AgentEvent.TurnFailed).count();
        assertEquals(1, failedCount);

        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof AgentEvent.TokenEmitted token
                                                && token.text().equals("He")));
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ModelCompleted));
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.TurnCompleted));

        assertTrue(store.appended.isEmpty());
    }

    @Test
    void storeLoadFailureEmitsTurnFailedWithoutInvokingGateway() {
        var store = new FakeStore();
        store.loadThrows = true;
        var gateway = new FakeGateway(Multi.createFrom().items("Hel", "lo"));
        var runtime = new AgentRuntime(store, gateway);

        List<AgentEvent> events =
                runtime.execute(TurnRequest.of("s1", "hi"))
                        .collect()
                        .asList()
                        .await()
                        .atMost(Duration.ofSeconds(5));

        assertEquals(2, events.size());
        assertInstanceOf(AgentEvent.TurnStarted.class, events.get(0));
        var failed = assertInstanceOf(AgentEvent.TurnFailed.class, events.get(1));
        assertTrue(failed.reason().contains("store down"));

        assertNull(gateway.receivedPrompt);
        assertTrue(store.appended.isEmpty());
    }

    @Test
    void resetDelegatesToStoreDelete() {
        var store = new FakeStore();
        var gateway = new FakeGateway(Multi.createFrom().items("Hel", "lo"));
        var runtime = new AgentRuntime(store, gateway);

        runtime.reset("s9");

        assertEquals(List.of("s9"), store.deleted);
        assertTrue(store.appended.isEmpty());
        assertNull(gateway.receivedPrompt);
    }

    @Test
    void appendFailureEmitsSingleTurnFailed() {
        var store = new FakeStore();
        store.appendThrows = true;
        var gateway = new FakeGateway(Multi.createFrom().items("Hel", "lo"));
        var runtime = new AgentRuntime(store, gateway);

        List<AgentEvent> events =
                runtime.execute(TurnRequest.of("s1", "hi"))
                        .collect()
                        .asList()
                        .await()
                        .atMost(Duration.ofSeconds(5));

        List<String> tokens = new ArrayList<>();
        for (AgentEvent e : events) {
            if (e instanceof AgentEvent.TokenEmitted token) {
                tokens.add(token.text());
            }
        }
        assertEquals(List.of("Hel", "lo"), tokens);

        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ModelCompleted));
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.TurnCompleted));

        long terminalCount =
                events.stream()
                        .filter(
                                e ->
                                        e instanceof AgentEvent.TurnCompleted
                                                || e instanceof AgentEvent.TurnFailed)
                        .count();
        assertEquals(1, terminalCount);

        var failed = assertInstanceOf(AgentEvent.TurnFailed.class, events.get(events.size() - 1));
        assertTrue(failed.reason().contains("append down"));
    }

    @Test
    void blankCompletionEmitsTurnCompletedButPersistsNothing() {
        var store = new FakeStore();
        var gateway = new FakeGateway(Multi.createFrom().empty());
        var runtime = new AgentRuntime(store, gateway);

        List<AgentEvent> events =
                runtime.execute(TurnRequest.of("s1", "hi"))
                        .collect()
                        .asList()
                        .await()
                        .atMost(Duration.ofSeconds(5));

        var completed = assertInstanceOf(AgentEvent.TurnCompleted.class, events.get(events.size() - 1));
        assertEquals("", completed.text());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ModelCompleted));
        assertTrue(
                store.appended.isEmpty(),
                "a blank completion must persist nothing — the renderer shows the error"
                        + " fallback, and memory must agree the turn did not happen");
    }
}
