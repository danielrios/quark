package com.quark.provider.gemini;

import com.quark.core.ChatMessage;
import com.quark.provider.ModelGateway;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The only langchain4j-touching package (ADR 0002 boundaries).
 *
 * <p>Wraps the {@link StreamingChatModel} bean that quarkus-langchain4j produces from
 * {@code quarkus.langchain4j.ai.gemini.*} config — injectable without any
 * {@code @RegisterAiService} (quarkiverse docs, models page).
 *
 * <p>Returns a cold {@link Multi} — the model call happens per subscription.
 *
 * <p>Downstream cancellation (e.g. the Telegram adapter's 60 s timeout) detaches
 * the emitter but cannot abort the model call: langchain4j's streaming handler
 * API exposes no cancel handle, so the underlying stream runs to completion and
 * late emissions are silently dropped. Bounded waste, no crash — future gateways
 * (Plan 5 NIM) should replicate these semantics knowingly.
 */
@ApplicationScoped
public class GeminiModelGateway implements ModelGateway {

    private final StreamingChatModel model;

    @Inject
    public GeminiModelGateway(StreamingChatModel model) {
        this.model = model;
    }

    @Override
    public Multi<String> stream(List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> mapped =
            history.stream().map(GeminiModelGateway::toLangChainMessage).toList();
        ChatRequest request = ChatRequest.builder().messages(mapped).build();

        return Multi.createFrom().emitter(emitter -> model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                emitter.emit(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                emitter.fail(error);
            }
        }), BackPressureStrategy.BUFFER);
    }

    private static dev.langchain4j.data.message.ChatMessage toLangChainMessage(ChatMessage message) {
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.text());
            case USER -> UserMessage.from(message.text());
            case ASSISTANT -> AiMessage.from(message.text());
        };
    }
}
