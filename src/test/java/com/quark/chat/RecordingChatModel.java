package com.quark.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plain (non-CDI) fake {@link ChatModel} for {@code QuarkusMock.installMockForType}. It records
 * the exact message list the AI service hands it on each call and returns a deterministic reply —
 * no network, no Gemini, no API key. Recording the inbound messages lets a test assert that turn N
 * actually carries the history of turns 1..N-1, i.e. that conversation memory is genuinely replayed
 * into the model across requests rather than merely written to a store.
 */
public class RecordingChatModel implements ChatModel {

    /** One entry per model invocation: the full message list the AI service sent. */
    public final List<List<ChatMessage>> receivedTurns = new CopyOnWriteArrayList<>();

    @Override
    public ChatResponse chat(ChatRequest request) {
        receivedTurns.add(List.copyOf(request.messages()));
        return ChatResponse.builder()
                .aiMessage(AiMessage.from("ack-" + receivedTurns.size()))
                .build();
    }
}
