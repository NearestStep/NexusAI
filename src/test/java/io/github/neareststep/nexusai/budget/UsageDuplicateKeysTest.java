package io.github.neareststep.nexusai.budget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UsageDuplicateKeysTest {

    /** A 1.3.0 file wrote two names that differed only in a lone surrogate as the same "?" key. */
    @Test
    void duplicateKeysFromAnOldFileAreSummed() {
        String old = """
                format: %d
                day: "2026-10-20"
                server:
                  requests: 7
                  prompt: 70
                  completion: 7
                  total: 77
                  estimated: 0
                providers: {}
                rows:
                  "groq|qa-fb?":
                    requests: 1
                    prompt: 10
                    completion: 1
                    total: 11
                    estimated: 0
                  "groq|qa-fb?":
                    requests: 3
                    prompt: 30
                    completion: 3
                    total: 33
                    estimated: 1
                fallback: {}
                origins: {}
                consumers:
                  qa?p:
                    requests: 2
                    prompt: 20
                    completion: 2
                    total: 22
                    estimated: 0
                  qa?p:
                    requests: 2
                    prompt: 20
                    completion: 2
                    total: 22
                    estimated: 0
                  other:
                    requests: 1
                    prompt: 1
                    completion: 1
                    total: 2
                    estimated: 0
                players: {}
                """.formatted(TokenUsageFile.FORMAT);
        TokenLedger.Snapshot snapshot = TokenUsageFile.parse(old);
        TokenLedger.Counts row = snapshot.rows().get("groq|qa-fb?");
        assertNotNull(row);
        assertEquals(4, row.requests());
        assertEquals(44, row.total());
        assertEquals(1, row.estimated());
        assertEquals(4, snapshot.consumers().get("qa?p").requests());
        assertEquals(1, snapshot.consumers().get("other").requests());
        assertEquals(2, snapshot.consumers().size());
        assertEquals(7, snapshot.server().requests());
    }

    @Test
    void aFileWithoutDuplicatesReadsAsBefore() {
        TokenLedger.Counts one = new TokenLedger.Counts(1, 10, 1, 11, 0);
        TokenLedger.Counts two = new TokenLedger.Counts(2, 20, 2, 22, 1);
        TokenLedger.Snapshot snapshot = new TokenLedger.Snapshot(
                java.time.LocalDate.parse("2026-10-20"),
                new TokenLedger.Counts(3, 30, 3, 33, 1),
                java.util.Map.of("openai", one),
                java.util.Map.of("openai|a", one, "groq|b", two),
                java.util.Map.of(),
                java.util.Map.of("API", two),
                java.util.Map.of("nexusai", one),
                java.util.Map.of(),
                java.util.List.of());
        String text = TokenUsageFile.render(snapshot, java.time.OffsetDateTime.parse("2026-10-20T10:00:00+03:00"), java.util.List.of());
        TokenLedger.Snapshot read = TokenUsageFile.parse(text);
        assertEquals(snapshot.rows(), read.rows());
        assertEquals(snapshot.providers(), read.providers());
        assertEquals(snapshot.origins(), read.origins());
        assertEquals(snapshot.consumers(), read.consumers());
    }
}
