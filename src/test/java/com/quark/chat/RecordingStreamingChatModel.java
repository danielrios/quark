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
 * Records the message list handed to it on each {@link #chat} call and emits a deterministic
 * "ack-N" reply as one partial response plus completion.
 *
 * <p>Used by {@code TelegramStreamingMemoryTest} and {@code TelegramConversationMemoryTest} to
 * prove cross-turn history is replayed through the runtime path
 * ({@code AgentRuntime → GeminiModelGateway → StreamingChatModel}) — see ADR 0007.
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
