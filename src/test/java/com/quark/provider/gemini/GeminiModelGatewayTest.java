package com.quark.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quark.core.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Gateway bridge contract: chunk order, failure propagation (runtime maps failures to events, not
 * the gateway), core→langchain4j role mapping.
 */
class GeminiModelGatewayTest {

    /** Fake {@link StreamingChatModel} that runs a scripted handler callback synchronously. */
    private static final class ScriptedStreamingChatModel implements StreamingChatModel {

        public ChatRequest lastRequest;
        private final Consumer<StreamingChatResponseHandler> script;

        ScriptedStreamingChatModel(Consumer<StreamingChatResponseHandler> script) {
            this.script = script;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            this.lastRequest = request;
            script.accept(handler);
        }
    }

    @Test
    void chunksEmittedInOrderThenCompletion() {
        var model = new ScriptedStreamingChatModel(handler -> {
            handler.onPartialResponse("Hel");
            handler.onPartialResponse("lo");
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from("Hello")).build());
        });
        var gateway = new GeminiModelGateway(model);
        List<ChatMessage> history = List.of(new ChatMessage(ChatMessage.Role.USER, "hi"));

        List<String> collected =
                gateway.stream(history)
                        .collect()
                        .asList()
                        .await()
                        .atMost(Duration.ofSeconds(5));

        assertEquals(List.of("Hel", "lo"), collected);
    }

    @Test
    void modelErrorPropagatesAsMultiFailure() {
        var model = new ScriptedStreamingChatModel(
                handler -> handler.onError(new IllegalStateException("boom")));
        var gateway = new GeminiModelGateway(model);
        List<ChatMessage> history = List.of(new ChatMessage(ChatMessage.Role.USER, "hi"));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> gateway.stream(history)
                                .collect()
                                .asList()
                                .await()
                                .atMost(Duration.ofSeconds(5)));

        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void roleMappingMatchesLangchain4jTypes() {
        var model = new ScriptedStreamingChatModel(
                handler -> handler.onCompleteResponse(
                        ChatResponse.builder().aiMessage(AiMessage.from("done")).build()));
        var gateway = new GeminiModelGateway(model);
        List<ChatMessage> history =
                List.of(
                        new ChatMessage(ChatMessage.Role.SYSTEM, "sys"),
                        new ChatMessage(ChatMessage.Role.USER, "u1"),
                        new ChatMessage(ChatMessage.Role.ASSISTANT, "a1"),
                        new ChatMessage(ChatMessage.Role.USER, "u2"));

        gateway.stream(history).collect().asList().await().atMost(Duration.ofSeconds(5));

        var messages = model.lastRequest.messages();

        assertEquals(4, messages.size());
        assertEquals("sys", assertInstanceOf(SystemMessage.class, messages.get(0)).text());
        assertEquals("u1", assertInstanceOf(UserMessage.class, messages.get(1)).singleText());
        assertEquals("a1", assertInstanceOf(AiMessage.class, messages.get(2)).text());
        assertEquals("u2", assertInstanceOf(UserMessage.class, messages.get(3)).singleText());
    }
}
