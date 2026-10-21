package io.github.neareststep.nexusai.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Importing prompts whose text has an escaped lone UTF-16 surrogate writes a valid prompts.yml. */
class PromptImporterLoneSurrogateTest {

    @Test
    void blockAndFlowPromptsWithAnEscapedLoneSurrogateAreImported(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("prompts.yml"), "config-version: 1\nexisting: \"here\"\n", StandardCharsets.UTF_8);
        Path imports = Files.createDirectories(dir.resolve("import"));
        Files.writeString(imports.resolve("pack.yml"), """
                block:
                  prompt: "tip \\ud83d"
                  fallback-model:
                    provider: groq
                    model: "qa-fb\\ud83d"
                ? flow
                : "flow \\ud83d"
                """, StandardCharsets.UTF_8);

        PromptImporter.Report report = PromptImporter.importFile(dir, "pack.yml", false);
        assertTrue(report.success(), report.error());
        assertTrue(report.changed());

        String prompts = Files.readString(dir.resolve("prompts.yml"), StandardCharsets.UTF_8);
        PromptCatalog.Parsed parsed = PromptCatalog.parse(prompts);
        assertTrue(parsed.valid(), parsed.error());
        assertEquals("here", parsed.catalog().find("existing").orElseThrow().template());
        assertEquals("qa-fb\uFFFD", parsed.catalog().find("block").orElseThrow().fallbackModel().model());
        assertTrue(parsed.catalog().find("block").orElseThrow().template().startsWith("tip "));
        assertTrue(parsed.catalog().find("flow").orElseThrow().template().startsWith("flow "));
        assertEquals(1, topLevelCount(prompts, "flow"), prompts);
    }

    @Test
    void explicitKeyAfterABlockIsNotCopiedTwice(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("prompts.yml"), "config-version: 1\nexisting: \"here\"\n", StandardCharsets.UTF_8);
        Path imports = Files.createDirectories(dir.resolve("import"));
        Files.writeString(imports.resolve("pack.yml"), """
                block:
                  prompt: "tip"
                ? flow
                : "flow text"
                """, StandardCharsets.UTF_8);

        PromptImporter.Report report = PromptImporter.importFile(dir, "pack.yml", false);
        assertTrue(report.success(), report.error());
        String prompts = Files.readString(dir.resolve("prompts.yml"), StandardCharsets.UTF_8);
        assertEquals(1, topLevelCount(prompts, "flow"), prompts);
        assertEquals(0, prompts.lines().filter(line -> line.startsWith("? ") || line.startsWith(": ")).count(), prompts);
        PromptCatalog.Parsed parsed = PromptCatalog.parse(prompts);
        assertTrue(parsed.valid(), parsed.error());
        assertEquals("tip", parsed.catalog().find("block").orElseThrow().template());
        assertEquals("flow text", parsed.catalog().find("flow").orElseThrow().template());
    }

    private static long topLevelCount(String yaml, String id) {
        return yaml.lines().filter(line -> line.startsWith(id + ":")).count();
    }
}
