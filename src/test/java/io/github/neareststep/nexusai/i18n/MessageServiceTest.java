package io.github.neareststep.nexusai.i18n;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void templateColoursAreTranslatedBeforeTheReplyIsInserted() {
        YamlConfiguration en = new YamlConfiguration();
        en.set("prefix", "&8[&bNexusAI&8]&r ");
        en.set("talk.reply", "{prefix}&f{character}&7: &f{reply}");
        en.set("command.test-ok", "{prefix}&aAnswer: &f{answer}");

        MessageService messages = MessageService.forTest(en, en, "en");
        String reply = "&cAMPRED &#FF0000AMPHASH &x&f&f&0&0&0&0AMPHEX &lAMPBOLD &kAMPMAGIC";
        String talk = messages.format("talk.reply", Map.of("character", "npc", "reply", reply));
        String test = messages.format("command.test-ok", Map.of("answer", reply));

        assertTrue(talk.contains("§f"));
        assertTrue(talk.contains("&cAMPRED"));
        assertTrue(talk.contains("&#FF0000AMPHASH"));
        assertTrue(talk.contains("&x&f&f&0&0&0&0AMPHEX"));
        assertTrue(talk.contains("&kAMPMAGIC"));
        assertFalse(talk.contains("§cAMPRED"));
        assertTrue(test.contains("&cAMPRED"));
        assertFalse(test.contains("§cAMPRED"));
        assertTrue(test.indexOf('§') >= 0);
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
    void mergeOrderIsUserThenBundledLocaleThenEnglish() {
        org.bukkit.configuration.file.YamlConfiguration user = new org.bukkit.configuration.file.YamlConfiguration();
        user.set("command.version", "user");
        org.bukkit.configuration.file.YamlConfiguration bundled = new org.bukkit.configuration.file.YamlConfiguration();
        bundled.set("command.version", "bundled");
        bundled.set("command.reload-ok", "bundled-reload");
        org.bukkit.configuration.file.YamlConfiguration english = new org.bukkit.configuration.file.YamlConfiguration();
        english.set("command.version", "english");
        english.set("command.reload-ok", "english-reload");
        english.set("command.help", "english-help");

        org.bukkit.configuration.file.YamlConfiguration merged = MessageService.merge(user, bundled, english);
        assertEquals("user", merged.getString("command.version"));
        assertEquals("bundled-reload", merged.getString("command.reload-ok"));
        assertEquals("english-help", merged.getString("command.help"));
        assertEquals(2, MessageService.filledFromDefaults(user, merged));
    }

    @Test
    void customLocaleFallsBackToEnglish() {
        org.bukkit.configuration.file.YamlConfiguration user = new org.bukkit.configuration.file.YamlConfiguration();
        user.set("command.version", "Ahoy {version}");
        org.bukkit.configuration.file.YamlConfiguration english = new org.bukkit.configuration.file.YamlConfiguration();
        english.set("prefix", "");
        english.set("command.version", "Version {version}");
        english.set("command.reload-ok", "{prefix}OK");

        MessageService messages = MessageService.forTest(
                MessageService.merge(user, new org.bukkit.configuration.file.YamlConfiguration(), english),
                english,
                "pirate");
        assertEquals("Ahoy 1", messages.format("command.version", java.util.Map.of("version", "1")));
        assertEquals("OK", messages.format("command.reload-ok"));
    }

    @Test
    void normalizeLocaleAcceptsHyphen() {
        assertEquals("pt_BR", MessageService.normalizeLocale("pt-BR"));
        assertEquals("pt_BR", MessageService.normalizeLocale("PT-br"));
        assertEquals("ru", MessageService.normalizeLocale("RU"));
        assertEquals("ru", MessageService.normalizeLocale("ru"));
        assertEquals("en", MessageService.normalizeLocale(""));
    }
}
