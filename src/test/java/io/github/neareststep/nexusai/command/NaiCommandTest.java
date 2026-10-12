package io.github.neareststep.nexusai.command;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.budget.UsageReport;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.context.ContextService;
import io.github.neareststep.nexusai.context.RegionOwnership;
import io.github.neareststep.nexusai.context.RegionPlayerFixture;
import io.github.neareststep.nexusai.i18n.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaiCommandTest {

    @AfterEach
    void resetRegionOwnership() {
        RegionOwnership.reset();
    }

    @Test
    void testDefersAndSkipsTheWorldWhenTheRegionIsNotOwned() {
        AtomicBoolean touched = new AtomicBoolean();
        RegionOwnership.install(player -> false);
        Player player = RegionPlayerFixture.throwing(touched);
        assertTrue(NaiCommand.deferTestToOwner(player));
        assertEquals("", NaiCommand.testContextWorld(player));
        assertFalse(touched.get());
    }

    @Test
    void testReadsTheWorldWhenTheRegionIsOwned() {
        RegionOwnership.install(player -> true);
        Player player = RegionPlayerFixture.named("Steve", "lobby");
        assertFalse(NaiCommand.deferTestToOwner(player));
        assertEquals("lobby", NaiCommand.testContextWorld(player));
    }

    @Test
    void consoleAndRconUsageReplyBeforeTheCallReturns() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("prefix", "");
        yaml.set("command.usage-header", "Token usage today:");
        yaml.set("command.usage-line", "- {name}: {tokens}");
        MessageService messages = new MessageService(yaml, new YamlConfiguration(), "en");
        List<UsageReport.Line> lines = List.of(
                new UsageReport.Line("command.usage-header", Map.of()),
                new UsageReport.Line("command.usage-line", Map.of("name", "sk-secret", "tokens", "4")));
        List<String> sent = new ArrayList<>();
        CommandSender console = recordingSender(ConsoleCommandSender.class, sent);
        assertTrue(NaiCommand.usageRepliesInline(console));
        NaiCommand.sendUsage(console, messages, lines, text -> text.replace("sk-secret", "hidden"));
        assertTrue(sent.stream().anyMatch(line -> line.contains("Token usage today:")), sent.toString());
        assertTrue(sent.stream().anyMatch(line -> line.contains("4")), sent.toString());
        assertTrue(sent.stream().anyMatch(line -> line.contains("hidden")), sent.toString());
        assertFalse(sent.stream().anyMatch(line -> line.contains("sk-secret")), sent.toString());

        assertTrue(NaiCommand.usageRepliesInline(recordingSender(RemoteConsoleCommandSender.class, new ArrayList<>())));
        assertFalse(NaiCommand.usageRepliesInline(RegionPlayerFixture.named("Steve", "lobby")));
    }

    private static CommandSender recordingSender(Class<? extends CommandSender> type, List<String> sent) {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName()) && args != null) {
                        for (Object arg : args) {
                            if (arg instanceof Component component) {
                                sent.add(PlainTextComponentSerializer.plainText().serialize(component));
                            } else if (arg instanceof String text) {
                                sent.add(text);
                            }
                        }
                    }
                    if ("getName".equals(method.getName())) {
                        return "sender";
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    @Test
    void consoleTestIsNotDeferred() {
        assertFalse(NaiCommand.deferTestToOwner(null));
        assertEquals("", NaiCommand.testContextWorld(null));
    }

    @Test
    void missingServerMeansThePlayerIsNotOwned() {
        Player player = RegionPlayerFixture.named("Steve", "lobby");
        assertFalse(RegionOwnership.installed());
        assertTrue(NaiCommand.deferTestToOwner(player));
        assertEquals("", NaiCommand.testContextWorld(player));
    }

    @Test
    void testTraceNamesThePlayerAndTheNotice() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CallTrace named = NaiCommand.testTrace(player, GenerationOverrides.none().withNoticeId("rules"));
        assertEquals(RequestOrigin.TEST, named.origin());
        assertEquals("nexusai", named.consumer());
        assertEquals(player, named.playerId());
        assertEquals("rules", named.promptId());
        assertEquals("", named.label());
        assertTrue(named.startedNanos() > 0L);
        CallTrace console = NaiCommand.testTrace(null, GenerationOverrides.none());
        assertNull(console.playerId());
        assertEquals("", console.promptId());
    }

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

    @Test
    void contextStatusLinesDoNotIncludePlayerValues() {
        ZoneId zone = ZoneId.of("UTC");
        String ok = new ContextService.StatusRow("economy", "MyEco", 10, 100, false, 0, 0, zone).format();
        assertEquals("economy (MyEco) prio 10, 100ms, ok, timeouts 0", ok);
        long until = Instant.parse("2026-10-12T18:03:11Z").toEpochMilli();
        String suspended = new ContextService.StatusRow("rank", "RankBridge", 100, 200, true, until, 7, zone).format();
        assertEquals("rank (RankBridge) prio 100, 200ms, suspended until 2026-10-12 18:03:11, timeouts 7", suspended);
        assertFalse(ok.contains("~12k"));
        assertFalse(suspended.contains("balance"));
        assertFalse(suspended.contains("§"));
    }
}
