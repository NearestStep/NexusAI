package io.github.neareststep.nexusai.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ChatCompletionResponse {

    private List<Choice> choices;

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
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
