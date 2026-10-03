package io.github.neareststep.nexusai.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void errorAndCharacterCannotColourTheTemplate() {
        YamlConfiguration en = new YamlConfiguration();
        en.set("prefix", "");
        en.set("talk.reply", "&f{character}&7: &f{reply}");
        en.set("talk.failed", "&cNo reply: &f{error}");
        en.set("command.test-fail", "&cTest failed: &f{error}");
        MessageService messages = MessageService.forTest(en, en, "en");

        String character = "§c<click:run_command:/op me>Bob";
        String error = "§4<hover:show_text:'hello>world'>nope {^_^} <3";
        Component talk = messages.component("talk.reply", Map.of("character", character, "reply", "§c<red>hi</red>"));
        Component failed = messages.component("talk.failed", Map.of("error", error));
        String plainTalk = PlainTextComponentSerializer.plainText().serialize(talk);
        String plainFailed = PlainTextComponentSerializer.plainText().serialize(failed);

        assertTrue(plainTalk.contains("Bob"), plainTalk);
        assertFalse(plainTalk.contains("click"), plainTalk);
        assertTrue(plainTalk.contains("§c<red>hi</red>"), plainTalk);
        TextComponent bob = find(talk, "Bob");
        assertEquals(NamedTextColor.WHITE, bob.color());
        assertNull(bob.clickEvent());
        assertNull(talk.clickEvent());

        assertTrue(plainFailed.contains("nope"), plainFailed);
        assertTrue(plainFailed.contains("{^_^}"), plainFailed);
        assertTrue(plainFailed.contains("<3"), plainFailed);
        assertFalse(plainFailed.contains("hover"), plainFailed);
        assertFalse(plainFailed.contains("§"), plainFailed);
        TextComponent nope = find(failed, "nope {^_^} <3");
        assertEquals(NamedTextColor.WHITE, nope.color());

        String formatted = messages.format("command.test-fail", Map.of("error", "§cboom &c& <click:run_command:/op me>x"));
        assertTrue(formatted.contains("boom"), formatted);
        assertTrue(formatted.contains("x"), formatted);
        assertFalse(formatted.contains("§cboom"), formatted);
        assertFalse(formatted.contains("click"), formatted);
        assertTrue(messages.format("command.test-fail", Map.of("error", "rock & stone")).contains("rock & stone"));
    }

    @Test
    void missingLocaleWarnsWithTheFileAndTheFallback() {
        Logger logger = Logger.getLogger("locale-missing-" + java.util.UUID.randomUUID());
        List<LogRecord> records = capture(logger);
        File expected = new File("plugins/NexusAI/lang/xx.yml");
        MessageService.warnMissingLocale(logger, "xx", expected);
        List<LogRecord> warnings = records.stream().filter(record -> record.getLevel() == Level.WARNING).toList();
        assertEquals(2, warnings.size());
        String first = warnings.get(0).getMessage();
        String second = warnings.get(1).getMessage();
        assertTrue(first.contains("plugins/NexusAI/lang/xx.yml"), first);
        assertTrue(first.contains("xx"), first);
        assertTrue(first.contains("Using English"), first);
        assertFalse(first.contains("missing in jar"), first);
        assertNull(warnings.get(0).getThrown());
        assertTrue(second.contains("ru"), second);
        assertTrue(second.contains("en"), second);
        assertNull(warnings.get(1).getThrown());
        assertTrue(records.stream().noneMatch(record -> record.getLevel().intValue() >= Level.SEVERE.intValue()));
    }

    @Test
    void brokenLocaleWarnsWithLineAndColumnAndHidesTheStack() throws InvalidConfigurationException {
        Logger logger = Logger.getLogger("locale-broken-" + java.util.UUID.randomUUID());
        List<LogRecord> records = capture(logger);
        YamlConfiguration yaml = new YamlConfiguration();
        InvalidConfigurationException error = assertThrows(
                InvalidConfigurationException.class,
                () -> yaml.loadFromString("items: [\n  this is not yaml\n"));
        File file = new File("plugins/NexusAI/lang/ru.yml");
        MessageService.warnBrokenLocale(logger, file, error, "Using the bundled ru locale.");
        List<LogRecord> warnings = records.stream().filter(record -> record.getLevel() == Level.WARNING).toList();
        assertEquals(1, warnings.size());
        String message = warnings.getFirst().getMessage();
        assertTrue(message.contains("plugins/NexusAI/lang/ru.yml"), message);
        assertTrue(message.contains("line"), message);
        assertTrue(message.contains("column"), message);
        assertTrue(message.contains("Using the bundled ru locale."), message);
        assertFalse(message.contains("\n"), message);
        assertNull(warnings.getFirst().getThrown());
        List<LogRecord> fine = records.stream().filter(record -> record.getLevel() == Level.FINE).toList();
        assertEquals(1, fine.size());
        assertTrue(fine.getFirst().getThrown() == error || fine.getFirst().getThrown() != null);
        assertEquals(error, fine.getFirst().getThrown());
        String reason = MessageService.yamlReason(error);
        assertTrue(reason.contains("line"), reason);
        assertTrue(reason.contains("column"), reason);
        assertFalse(reason.contains("\n"), reason);
    }

    private static TextComponent find(Component component, String exact) {
        TextComponent found = search(component, exact);
        if (found == null) {
            throw new AssertionError("missing " + exact + " in " + PlainTextComponentSerializer.plainText().serialize(component));
        }
        return found;
    }

    private static TextComponent search(Component component, String exact) {
        if (component instanceof TextComponent text && exact.equals(text.content())) {
            return text;
        }
        for (Component child : component.children()) {
            TextComponent found = search(child, exact);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<LogRecord> capture(Logger logger) {
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return records;
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
