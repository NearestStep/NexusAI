package io.github.neareststep.nexusai.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ChatCompletionRequest {

    private final String model;
    private final List<Message> messages;
    private final Double temperature;
    private final Integer maxTokens;
    private final Integer maxCompletionTokens;
    private final String reasoningEffort;

    public ChatCompletionRequest(
            String model,
            List<Message> messages,
            Double temperature,
            Integer maxTokens,
            Integer maxCompletionTokens,
            String reasoningEffort
    ) {
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxCompletionTokens = maxCompletionTokens;
        this.reasoningEffort = reasoningEffort;
    }

    public String getModel() {
        return model;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public Double getTemperature() {
        return temperature;
    }

    @JsonProperty("max_tokens")
    public Integer getMaxTokens() {
        return maxTokens;
    }

    @JsonProperty("max_completion_tokens")
    public Integer getMaxCompletionTokens() {
        return maxCompletionTokens;
    }

    @JsonProperty("reasoning_effort")
    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public static final class Message {
        private final String role;
        private final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}
