package io.github.neareststep.nexusai.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void deserializesContentArrayOfParts() throws Exception {
        String json = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": [
                          {"type": "text", "text": "Hello"},
                          {"type": "output_text", "text": " world"}
                        ]
                      }
                    }
                  ]
                }
                """;

        ChatCompletionResponse response = new ObjectMapper().readValue(json, ChatCompletionResponse.class);
        assertEquals("Hello world", response.getChoices().getFirst().getMessage().getContent());
    }

    @Test
    void bodyWithoutUsageParsesLike111() throws Exception {
        String json = """
                {
                  "id": "chatcmpl-1",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": "Hello there" },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;
        ChatCompletionResponse response = new ObjectMapper().readValue(json, ChatCompletionResponse.class);
        assertEquals("Hello there", response.getChoices().getFirst().getMessage().getContent());
        assertEquals("stop", response.getChoices().getFirst().getFinishReason());
        assertEquals(ResponseUsage.none(), response.usage());
        assertFalse(response.usage().reported());
        assertFalse(response.usage().estimated());
    }

    @Test
    void fullUsageKeepsCostAndIgnoresUnknownFields() throws Exception {
        String json = """
                {
                  "choices": [ { "message": { "content": "pong" }, "finish_reason": "stop" } ],
                  "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                    "cost": 0.0125,
                    "prompt_tokens_details": { "cached_tokens": 4 }
                  }
                }
                """;
        ResponseUsage usage = new ObjectMapper().readValue(json, ChatCompletionResponse.class).usage();
        assertEquals(100, usage.promptTokens());
        assertEquals(20, usage.completionTokens());
        assertEquals(120, usage.totalTokens());
        assertTrue(usage.reported());
        assertFalse(usage.estimated());
        assertEquals(0.0125d, usage.cost().orElseThrow());
    }

    @Test
    void partialUsageKeepsOnlyTheReportedTotal() throws Exception {
        String json = """
                { "choices": [ { "message": { "content": "pong" } } ], "usage": { "total_tokens": 9 } }
                """;
        ResponseUsage usage = new ObjectMapper().readValue(json, ChatCompletionResponse.class).usage();
        assertEquals(0, usage.promptTokens());
        assertEquals(0, usage.completionTokens());
        assertEquals(9, usage.totalTokens());
        assertTrue(usage.reported());
        assertFalse(usage.estimated());
        assertTrue(usage.cost().isEmpty());
    }

    @Test
    void missingTotalIsTheSumAndASmallerTotalIsRaised() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResponseUsage missing = mapper.readValue(
                "{\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}", ChatCompletionResponse.class).usage();
        assertEquals(7, missing.totalTokens());
        ResponseUsage smaller = mapper.readValue(
                "{\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":1}}",
                ChatCompletionResponse.class).usage();
        assertEquals(7, smaller.totalTokens());
    }

    @Test
    void negativesAndNonNumbersBecomeZero() throws Exception {
        String json = """
                { "usage": { "prompt_tokens": -5, "completion_tokens": "nope", "total_tokens": 2, "cost": "free" } }
                """;
        ResponseUsage usage = new ObjectMapper().readValue(json, ChatCompletionResponse.class).usage();
        assertEquals(0, usage.promptTokens());
        assertEquals(0, usage.completionTokens());
        assertEquals(2, usage.totalTokens());
        assertTrue(usage.cost().isEmpty());
        assertTrue(usage.reported());
    }

    @Test
    void nullOrNonObjectUsageIsNone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(ResponseUsage.none(), mapper.readValue("{\"usage\":null}", ChatCompletionResponse.class).usage());
        assertEquals(ResponseUsage.none(), mapper.readValue("{\"usage\":4}", ChatCompletionResponse.class).usage());
        ResponseUsage empty = mapper.readValue("{\"usage\":{}}", ChatCompletionResponse.class).usage();
        assertTrue(empty.reported());
        assertFalse(empty.estimated());
        assertEquals(0, empty.totalTokens());
    }
}
