package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeConfigTest {

    @Test
    void clampsKeywordSettingsAndRejectsUnknownModes() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "test-key");
        yaml.set("knowledge.select", "keywords");
        yaml.set("knowledge.keywords.max-paragraphs", 99);
        yaml.set("knowledge.keywords.max-paragraph-chars", 10);
        yaml.set("knowledge.keywords.max-file-chars", 50);
        yaml.set("knowledge.keywords.min-matches", 0);
        yaml.set("knowledge.keywords.on-no-match", "first");
        yaml.set("knowledge.keywords.stop-words", List.of(" dock ", ""));
        PluginConfig config = new PluginConfig(yaml);
        assertEquals(KnowledgeSelect.KEYWORDS, config.knowledgeSelect());
        assertEquals(50, config.keywordSettings().maxParagraphs());
        assertEquals(100, config.keywordSettings().maxParagraphChars());
        assertEquals(1000, config.keywordSettings().maxFileChars());
        assertEquals(1, config.keywordSettings().minMatches());
        assertEquals(KeywordSettings.OnNoMatch.FIRST, config.keywordSettings().onNoMatch());
        assertEquals(List.of("dock"), config.keywordSettings().stopWords());
        String warnings = config.knowledgeWarnings().toString();
        assertTrue(warnings.contains("max-paragraphs"), warnings);
        assertTrue(warnings.contains("max-paragraph-chars"), warnings);
        assertTrue(warnings.contains("max-file-chars"), warnings);
        assertTrue(warnings.contains("min-matches"), warnings);

        YamlConfiguration unknown = new YamlConfiguration();
        unknown.set("knowledge.select", "vectors");
        unknown.set("knowledge.keywords.on-no-match", "nearest");
        PluginConfig fallback = new PluginConfig(unknown);
        assertEquals(KnowledgeSelect.FULL, fallback.knowledgeSelect());
        assertEquals(KeywordSettings.OnNoMatch.NONE, fallback.keywordSettings().onNoMatch());
        String unknownWarnings = fallback.knowledgeWarnings().toString();
        assertTrue(unknownWarnings.contains("knowledge.select"), unknownWarnings);
        assertTrue(unknownWarnings.contains("on-no-match"), unknownWarnings);
    }
}
