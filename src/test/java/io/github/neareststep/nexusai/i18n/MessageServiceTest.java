package io.github.neareststep.nexusai.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceTest {

    @Test
    void formatsPlaceholdersAndColors() {
        YamlConfiguration en = new YamlConfiguration();
        en.set("prefix", "&8[&bNexusAI&8]&r ");
        en.set("command.version", "{prefix}&fVersion: &a{version}");

        MessageService messages = MessageService.forTest(en, en, "en");
        String formatted = messages.format("command.version", Map.of("version", "0.3.0"));

        assertTrue(formatted.contains("0.3.0"));
        assertTrue(formatted.contains("Version:"));
        assertTrue(formatted.indexOf('&') < 0);
    }

    @Test
    void missingKeyFallsBackToEnglish() {
        YamlConfiguration en = new YamlConfiguration();
        en.set("prefix", "[N] ");
        en.set("command.reload-ok", "{prefix}OK");

        YamlConfiguration de = new YamlConfiguration();
        de.set("prefix", "[N] ");

        MessageService messages = MessageService.forTest(de, en, "de");
        assertEquals("[N] OK", messages.format("command.reload-ok"));
    }

    @Test
    void normalizeLocaleAcceptsHyphen() {
        assertEquals("pt_BR", MessageService.normalizeLocale("pt-BR"));
        assertEquals("en", MessageService.normalizeLocale(""));
    }
}
