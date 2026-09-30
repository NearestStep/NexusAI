package io.github.neareststep.nexusai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live Groq replies from allam-2-7b and qwen3.8-27b, candidate 0.7.0 and baseline 0.6.0.
 * Every allam candidate restatement is rejected. No qwen reply and no normal benign answer is.
 */
class LiveGroqAnalysisTest {

    @Test
    void liveRepliesMatchTheDetector() throws Exception {
        var resource = LiveGroqAnalysisTest.class.getResourceAsStream("/live-groq-analysis.json");
        assertNotNull(resource);
        JsonNode rows = new ObjectMapper().readTree(resource);
        int rejected = 0;
        int kept = 0;
        int benignKept = 0;
        for (JsonNode row : rows) {
            String answer = row.path("answer").asText();
            boolean hit = PlayerInput.restatesGuard(answer);
            boolean candidate = "candidate".equals(row.path("jar").asText());
            boolean allamRestatement = candidate
                    && "allam".equals(row.path("model").asText())
                    && row.path("guard_ref").asBoolean();
            if (candidate) {
                if (allamRestatement) {
                    assertTrue(hit, () -> "expected reject: " + answer);
                    rejected++;
                } else {
                    assertFalse(hit, () -> "false reject: " + answer);
                    kept++;
                }
            }
            if ("benign".equals(row.path("kind").asText()) && !allamRestatement) {
                assertFalse(hit, () -> "benign false reject: " + answer);
                benignKept++;
            }
        }
        assertEquals(38, rejected);
        assertEquals(44, kept);
        assertTrue(benignKept >= 15);
    }
}
