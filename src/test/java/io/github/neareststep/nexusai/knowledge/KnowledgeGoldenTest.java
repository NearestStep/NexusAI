package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGoldenTest {

    @Test
    void fullModeBlockAndCacheTokenStayByteForByte(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lore.md"), "<!-- harbor note -->\n\nThe harbor is old.\n");
        KnowledgeBase knowledge = KnowledgeBase.load(dir, 6000, 4000, new ArrayList<>(), Logger.getLogger("knowledge-golden"));
        String expected = """
                ----- KNOWLEDGE -----
                [lore]
                <!-- harbor note -->

                The harbor is old.
                ----- END KNOWLEDGE -----""";
        assertEquals(expected, knowledge.block(List.of("lore")));
        KnowledgeBase.Piece full = knowledge.render(List.of("lore"), KnowledgeRequest.full());
        assertEquals(expected, full.block());
        assertEquals(knowledge.cacheToken(List.of("lore")), full.cacheToken());
        assertEquals("lore (full)", full.summary());

        KnowledgeComposer.Prepared prepared = KnowledgeComposer.prepare(
                GenerationOverrides.none(), "", knowledge, List.of("lore"));
        assertEquals(full.cacheToken(), prepared.cacheToken());
        assertTrue(prepared.overrides().systemPrompt("").contains("<!-- harbor note -->"));

        KnowledgeBase.Piece keywords = knowledge.render(
                List.of("lore"), new KnowledgeRequest(KnowledgeSelect.KEYWORDS, "harbor", List.of()));
        assertFalse(keywords.block().contains("<!--"), keywords.block());
        assertTrue(keywords.block().contains("The harbor is old."));
        assertNotEquals(full.cacheToken(), keywords.cacheToken());
        assertEquals("lore#1 (keywords)", keywords.summary());
    }
}
