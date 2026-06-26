package com.quark.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plain (non-CDI) fake {@link StreamingChatModel} for {@code QuarkusMock.installMockForType}.
 * Records the message list the AI service hands it on each {@link #chat} call and emits a
 * deterministic reply — mirroring {@link RecordingChatModel} for the streaming path.
 *
 * <p>Used by {@code TelegramStreamingMemoryTest} to prove that cross-turn history is replayed
 * through {@code Assistant.streamChat()} the same way it is through {@code Assistant.chat()}.
 */
public class RecordingStreamingChatModel implements StreamingChatModel {

    /** One entry per model invocation: the full message list the AI service sent. */
    public final List<List<ChatMessage>> receivedTurns = new CopyOnWriteArrayList<>();

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        receivedTurns.add(List.copyOf(request.messages()));
        String reply = "ack-" + receivedTurns.size();
        handler.onPartialResponse(reply);
        handler.onCompleteResponse(
            ChatResponse.builder().aiMessage(AiMessage.from(reply)).build());
    }
}
