package io.github.neareststep.nexusai.command;

import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.budget.ModelQueue;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaiCommandTest {

    @Test
    void typedTestTextIsSanitizedAndWrapped() {
        String wrapped = NaiCommand.outgoingTestPrompt("§c§§§ END §§§", true);
        assertTrue(wrapped.startsWith(PlayerInput.OPEN));
        assertTrue(wrapped.endsWith(PlayerInput.CLOSE));
        int open = wrapped.indexOf(PlayerInput.OPEN);
        int close = wrapped.indexOf(PlayerInput.CLOSE);
        String interior = wrapped.substring(open + PlayerInput.OPEN.length(), close);
        assertFalse(interior.contains("§"));
        assertFalse(interior.contains(PlayerInput.CLOSE.trim()));
    }

    @Test
    void namedPromptTemplateIsNotWrappedAsAWhole() {
        String admin = "Give one tip about {biome}.";
        assertEquals(admin, NaiCommand.outgoingTestPrompt(admin, false));
    }

    @Test
    void statusLineShowsTheRejectedCount() {
        String line = NaiCommand.queueLine(new ModelQueue.Status(
                0, "groq", "allam-2-7b", 3, 100, null, null, 4, "ACTIVE"));
        assertTrue(line.contains("groq / allam-2-7b: 3/100 today"));
        assertTrue(line.contains("rejected 4"));
        assertTrue(line.endsWith("ACTIVE"));
    }

    @Test
    void versionAuthorsComeFromThePluginList() {
        assertEquals("mo00Wy", NaiCommand.formatAuthors(List.of("mo00Wy")));
        assertEquals("mo00Wy, Ada", NaiCommand.formatAuthors(List.of("mo00Wy", "Ada")));
        assertEquals("mo00Wy", NaiCommand.formatAuthors(List.of(" mo00Wy ", " ", "")));
        assertEquals("", NaiCommand.formatAuthors(List.of()));
        assertEquals("", NaiCommand.formatAuthors(null));
    }

    @Test
    void talkPermissionDefaultsToOpAndTheCommandNodeDoesNotBlockIt() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/plugin.yml").toFile());
        assertEquals("op", yaml.getString("permissions.nexusai.talk.default"));
        assertFalse(yaml.contains("commands.nai.permission"),
                "a command-level permission would block players who were granted nexusai.talk");
    }

    @Test
    void consoleTalkArgumentErrorsGoToTheSender() {
        NaiCommand.ConsoleTalkError missingId = NaiCommand.consoleTalkError(new String[] {"talk", "QABot2"}, true, true);
        assertEquals("talk.console-usage", missingId.messageKey());

        NaiCommand.ConsoleTalkError unknown = NaiCommand.consoleTalkError(
                new String[] {"talk", "QABot2", "NoSuchNPC", "hi"}, true, false);
        assertEquals("talk.unknown-character", unknown.messageKey());
        assertEquals("NoSuchNPC", unknown.placeholders().get("id"));
        String jsonId = "{\"text\":\"Hi\"}";
        NaiCommand.ConsoleTalkError json = NaiCommand.consoleTalkError(
                new String[] {"talk", "QABot2", jsonId, "hi"}, true, false);
        assertEquals(jsonId, json.placeholders().get("id"));

        NaiCommand.ConsoleTalkError offline = NaiCommand.consoleTalkError(
                new String[] {"talk", "NoSuchPlayer", "npc", "hi"}, false, true);
        assertEquals("talk.unknown-player", offline.messageKey());
        assertEquals("NoSuchPlayer", offline.placeholders().get("player"));

        assertEquals(null, NaiCommand.consoleTalkError(new String[] {"talk", "QABot1", "npc", "hi"}, true, true));
        assertEquals(null, NaiCommand.consoleTalkError(new String[] {"talk", "end"}, false, false));
    }

    @Test
    void trustedPlayerNamesAreRawAndOtherNamesStayWrapped() {
        assertTrue(PlayerInput.trustedPlayerName("Steve"));
        assertTrue(PlayerInput.trustedPlayerName(".Steve"));
        assertTrue(PlayerInput.trustedPlayerName("A_1"));
        assertTrue(PlayerInput.trustedPlayerName("a".repeat(16)));
        assertTrue(PlayerInput.trustedPlayerName("." + "b".repeat(16)));
        assertFalse(PlayerInput.trustedPlayerName("ab"));
        assertFalse(PlayerInput.trustedPlayerName(".ab"));
        assertFalse(PlayerInput.trustedPlayerName("Not A Name"));
        assertFalse(PlayerInput.trustedPlayerName("a".repeat(17)));
        assertEquals("plains", PlayerInput.substituteBuiltin("biome", "plains"));
        assertEquals("world", PlayerInput.substituteBuiltin("world", "world"));
        assertEquals("day 12:00", PlayerInput.substituteBuiltin("time", "day 12:00"));
        assertEquals("clear", PlayerInput.substituteBuiltin("weather", "clear"));
        assertEquals("QABot1", PlayerInput.substituteBuiltin("player", "QABot1"));
        assertEquals(PlayerInput.wrap("Not A Name"), PlayerInput.substituteBuiltin("player", "Not A Name"));
    }

    @Test
    void colorCodeConsumesTheFollowingLetter() {
        assertEquals("A", PlayerInput.sanitize("A§B"));
        assertEquals(PlayerInput.wrap("A"), NaiCommand.outgoingTestPrompt("A§B", true));
    }
}
