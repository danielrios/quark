package com.quark.provider.nim;

import com.quark.core.ChatMessage;
import com.quark.provider.ModelGateway;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkiverse.langchain4j.ModelName;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;

/**
 * NIM provider gateway (Plan 5). Mirrors {@code GeminiModelGateway} — langchain4j
 * confined to this package (ADR 0002). Uses the OpenAI-compatible NIM API via
 * {@code quarkus-langchain4j-openai} pointed at {@code integrate.api.nvidia.com/v1}.
 *
 * <p>Downstream cancellation semantics: same as Gemini — detaches the emitter but
 * cannot abort the in-flight model call; late emissions are silently dropped.
 */
@ApplicationScoped
@Named("nim")
public class NimModelGateway implements ModelGateway {

    private final StreamingChatModel model;

    @Inject
    public NimModelGateway(@ModelName("nim") StreamingChatModel model) {
        this.model = model;
    }

    @Override
    public Multi<String> stream(List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> mapped =
                history.stream().map(NimModelGateway::toLangChainMessage).toList();
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
