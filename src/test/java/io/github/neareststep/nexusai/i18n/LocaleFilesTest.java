package io.github.neareststep.nexusai.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocaleFilesTest {

    private static final List<String> REQUIRED = List.of(
            "command.help-test",
            "command.help-prompts",
            "command.help-prompts-import",
            "command.prompts-import-ok",
            "command.prompts-import-fail",
            "command.status-fallback-model",
            "command.status-knowledge",
            "command.status-context",
            "command.status-context-line",
            "command.status-unpooled",
            "command.prompts-header",
            "command.prompts-line",
            "command.prompts-empty",
            "command.status-prompts",
            "command.test-sending",
            "command.test-ok",
            "command.test-fail",
            "command.status-last-error",
            "command.status-provider-pause",
            "command.status-queue-strategy",
            "command.status-moderation",
            "moderation.notify",
            "common.none",
            "common.yes",
            "common.no",
            "command.extra-args",
            "error.rate-limit",
            "error.quota",
            "error.bad-key",
            "error.unknown-model",
            "error.timeout",
            "error.other",
            "command.help-talk",
            "command.help-talk-end",
            "talk.disabled",
            "talk.players-only",
            "talk.usage",
            "talk.console-usage",
            "talk.unknown-character",
            "talk.unknown-player",
            "talk.no-session",
            "talk.ended",
            "talk.started",
            "talk.timeout",
            "talk.left",
            "talk.cooldown",
            "talk.too-long",
            "talk.replies",
            "talk.daily",
            "talk.busy",
            "talk.failed",
            "talk.empty",
            "talk.reply"
    );

    @Test
    void everyBundledLocaleHasTheNewKeys() throws Exception {
        List<Path> files;
        try (var stream = Files.list(Path.of("src/main/resources/lang"))) {
            files = stream.filter(path -> path.toString().endsWith(".yml")).sorted().toList();
        }
        assertEquals(15, files.size());
        for (Path file : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            for (String key : REQUIRED) {
                assertTrue(yaml.contains(key), file.getFileName() + " missing " + key);
                assertFalse(yaml.getString(key).isBlank(), file.getFileName() + " blank " + key);
            }
            String version = yaml.getString("command.version");
            assertTrue(version != null && version.contains("{version}"), file.getFileName() + " {version}");
            assertTrue(version.contains("{authors}"), file.getFileName() + " {authors}");
            if (!file.getFileName().toString().equals("en.yml")) {
                assertFalse(version.contains("Author:"), file.getFileName() + " version author still English");
            }
            String yes = yaml.getString("common.yes");
            String no = yaml.getString("common.no");
            assertTrue(yes != null && !yes.isBlank(), file.getFileName() + " yes");
            assertTrue(no != null && !no.isBlank(), file.getFileName() + " no");
            assertTrue(yaml.get("common.yes") instanceof String, file.getFileName() + " yes type");
            assertTrue(yaml.get("common.no") instanceof String, file.getFileName() + " no type");
            if (file.getFileName().toString().equals("ru.yml")) {
                assertEquals("нет ошибки", yaml.getString("common.none"));
                assertEquals("нет", no);
            }
            if (file.getFileName().toString().equals("ko.yml")) {
                assertFalse(yaml.getString("command.help-talk").contains("Talk to a character"),
                        "ko.yml help-talk still English");
                assertFalse(yaml.getString("talk.disabled").contains("Dialogues are disabled"),
                        "ko.yml talk.disabled still English");
                assertTrue(yaml.getString("talk.too-long").contains("{max}"), "ko.yml {max}");
                assertTrue(yaml.getString("talk.reply").contains("{character}"), "ko.yml {character}");
                assertTrue(yaml.getString("talk.reply").contains("{reply}"), "ko.yml {reply}");
            }
            if (!file.getFileName().toString().equals("en.yml")) {
                assertFalse(yaml.getString("command.help-prompts-import").contains("Import prompts from the import folder"),
                        file.getFileName() + " help-prompts-import still English");
                assertFalse(yaml.getString("command.status-unpooled").contains("requested but not pooled"),
                        file.getFileName() + " status-unpooled still English");
                assertTrue(yaml.getString("command.prompts-import-ok").contains("{file}"), file.getFileName() + " {file}");
                assertTrue(yaml.getString("command.prompts-import-ok").contains("{added}"), file.getFileName() + " {added}");
                assertTrue(yaml.getString("command.prompts-import-ok").contains("{skipped}"), file.getFileName() + " {skipped}");
                assertTrue(yaml.getString("command.prompts-import-ok").contains("{conflicting}"), file.getFileName() + " {conflicting}");
                assertTrue(yaml.getString("command.prompts-import-fail").contains("{error}"), file.getFileName() + " {error}");
                assertTrue(yaml.getString("command.status-fallback-model").contains("{entry}"), file.getFileName() + " {entry}");
                assertTrue(yaml.getString("command.status-knowledge").contains("{files}"), file.getFileName() + " {files}");
                assertTrue(yaml.getString("command.status-context").contains("{count}"), file.getFileName() + " {count}");
                assertTrue(yaml.getString("command.status-context-line").contains("{line}"), file.getFileName() + " {line}");
                assertTrue(yaml.getString("command.status-unpooled").contains("{prompts}"), file.getFileName() + " {prompts}");
                assertTrue(yaml.getString("command.status-unpooled").contains("generate_"), file.getFileName() + " generate_");
            }
        }
        assertEquals(15, LocaleFiles.BUNDLED.size());
    }

    @Test
    void extractionDoesNotOverwriteAnExistingLocaleFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path lang = dir.resolve("lang");
        Files.createDirectories(lang);
        Files.writeString(lang.resolve("en.yml"), "prefix: \"custom\"\n", StandardCharsets.UTF_8);
        java.util.Map<String, byte[]> bundled = new java.util.LinkedHashMap<>();
        for (String code : LocaleFiles.BUNDLED) {
            bundled.put(code, ("prefix: \"" + code + "\"\n").getBytes(StandardCharsets.UTF_8));
        }
        List<String> written = LocaleFiles.extractMissing(lang, code -> {
            byte[] bytes = bundled.get(code);
            return bytes == null ? null : new java.io.ByteArrayInputStream(bytes);
        });
        assertFalse(written.contains("en"));
        assertEquals("prefix: \"custom\"\n", Files.readString(lang.resolve("en.yml")));
        assertTrue(written.contains("de"));
        assertEquals("prefix: \"de\"\n", Files.readString(lang.resolve("de.yml")));
        List<String> again = LocaleFiles.extractMissing(lang, code -> new java.io.ByteArrayInputStream("prefix: \"nope\"\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(again.isEmpty());
        assertEquals("prefix: \"custom\"\n", Files.readString(lang.resolve("en.yml")));
    }
}
