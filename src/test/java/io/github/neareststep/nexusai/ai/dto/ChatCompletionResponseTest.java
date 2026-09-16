package io.github.neareststep.nexusai.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChatCompletionResponseTest {

    @Test
    void deserializesContent() throws Exception {
        String json = """
                {
                  "choices": [
                    { "message": { "role": "assistant", "content": "Hello there" } }
                  ]
                }
                """;

        ChatCompletionResponse response = new ObjectMapper().readValue(json, ChatCompletionResponse.class);

        assertNotNull(response.getChoices());
        assertEquals("Hello there", response.getChoices().getFirst().getMessage().getContent());
    }
}
