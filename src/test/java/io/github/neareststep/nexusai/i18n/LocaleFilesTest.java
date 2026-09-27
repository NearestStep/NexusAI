package io.github.neareststep.nexusai.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocaleFilesTest {

    private static final List<String> REQUIRED = List.of(
            "command.help-test",
            "command.test-sending",
            "command.test-ok",
            "command.test-fail",
            "command.status-last-error",
            "command.status-provider-pause",
            "common.none",
            "error.rate-limit",
            "error.quota",
            "error.bad-key",
            "error.unknown-model",
            "error.timeout",
            "error.other"
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
        }
    }
}
