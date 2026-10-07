package io.github.neareststep.nexusai.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.neareststep.nexusai.ai.ResponseUsage;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ChatCompletionResponse {

    private List<Choice> choices;
    private ResponseUsage usage = ResponseUsage.none();

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    /**
     * Provider {@code usage}, or {@link ResponseUsage#none()} when the field was absent.
     * An estimate is not applied here.
     */
    @JsonIgnore
    public ResponseUsage usage() {
        return usage == null ? ResponseUsage.none() : usage;
    }

    @JsonProperty("usage")
    public void setUsage(JsonNode node) {
        this.usage = UsageJson.read(node);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Choice {
        private Message message;
        private String finishReason;

        public Message getMessage() {
            return message;
        }

        public void setMessage(Message message) {
            this.message = message;
        }

        @JsonIgnore
        public String getFinishReason() {
            return finishReason;
        }

        @JsonProperty("finish_reason")
        public void setFinishReason(String finishReason) {
            this.finishReason = finishReason;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Message {
        private String role;
        private String content;
        private String reasoning;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        @JsonIgnore
        public String getContent() {
            return content;
        }

        @JsonProperty("content")
        public void setContent(JsonNode node) {
            this.content = ContentTexts.read(node);
        }

        @JsonProperty("reasoning_content")
        public void setReasoningContent(JsonNode node) {
            rememberReasoning(node);
        }

        @JsonProperty("reasoning")
        public void setReasoning(JsonNode node) {
            rememberReasoning(node);
        }

        @JsonProperty("reasoning_text")
        public void setReasoningText(JsonNode node) {
            rememberReasoning(node);
        }

        @JsonIgnore
        public String visibleText() {
            if (content != null && !content.isBlank()) {
                return content;
            }
            if (reasoning != null && !reasoning.isBlank()) {
                return reasoning;
            }
            return content;
        }

        private void rememberReasoning(JsonNode node) {
            if (reasoning != null && !reasoning.isBlank()) {
                return;
            }
            String text = ContentTexts.read(node);
            if (text != null && !text.isBlank()) {
                this.reasoning = text;
            }
        }
    }
}
