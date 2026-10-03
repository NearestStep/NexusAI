package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.i18n.MessageService;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyMarkupTest {

    @Test
    void stripsHexAndMiniMessageTagsButKeepsOrdinaryAngles() {
        assertEquals("Hi", PlayerInput.stripSectionSigns("<red>Hi</red>"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("<RED>Hi</RED>"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("<#FF0000>Hi</#FF0000>"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("<#F00>Hi</#F00>"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("<gradient:#FF0000:#00FF00>Hi</gradient>"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("<rainbow>Hi</rainbow>"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("<font:minecraft:default>Hi</font>"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("<!italic>Hi</!italic>"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("<bold>Hi</bold>"));
        assertEquals("x", PlayerInput.stripSectionSigns("<insert:hello>x</insert>"));
        assertEquals("", PlayerInput.stripSectionSigns("<key:key.jump>"));
        assertEquals("", PlayerInput.stripSectionSigns("<lang:block.minecraft.stone>"));
        assertEquals("", PlayerInput.stripSectionSigns("<selector:@p>"));
        assertEquals("", PlayerInput.stripSectionSigns("<score:obj>"));
        assertEquals("", PlayerInput.stripSectionSigns("<nbt:block>"));
        assertEquals("", PlayerInput.stripSectionSigns("<newline>"));
        assertEquals("", PlayerInput.stripSectionSigns("<newline/>"));
        assertEquals("", PlayerInput.stripSectionSigns("<reset>"));
        assertEquals("CLICK", PlayerInput.stripSectionSigns("<click:run_command:/say pwned>CLICK</click>"));
        assertEquals("CLICK", PlayerInput.stripSectionSigns("<click:run_command:'/op Steve'>CLICK</click>"));
        assertEquals("CLICK", PlayerInput.stripSectionSigns("<click:open_url:https://example.test>CLICK</click>"));
        assertEquals("", PlayerInput.stripSectionSigns("<hover:show_text:'hello'>"));
        assertEquals("tip", PlayerInput.stripSectionSigns("<hover:show_text:'<red>hi'>tip</hover>"));
        assertEquals("[ADMIN] Test", PlayerInput.stripSectionSigns("&#FF0000[ADMIN]&r Test"));
        assertEquals("[ADMIN] Test", PlayerInput.stripSectionSigns("&#ff00AA[ADMIN]&r Test"));
        assertEquals("Hi", PlayerInput.stripSectionSigns("&#F00Hi"));
        assertEquals("Say  now", PlayerInput.stripSectionSigns("Say &#ff00AA now"));
        assertEquals("&#FF00", PlayerInput.stripSectionSigns("&#FF00"));
        assertEquals("&#FF000", PlayerInput.stripSectionSigns("&#FF000"));
        assertEquals("rock & stone", PlayerInput.stripSectionSigns("rock & stone"));
        assertEquals("<3", PlayerInput.stripSectionSigns("<3"));
        assertEquals("I <3 you", PlayerInput.stripSectionSigns("I <3 you"));
        assertEquals("x < y", PlayerInput.stripSectionSigns("x < y"));
        assertEquals("a < b", PlayerInput.stripSectionSigns("a < b"));
        assertEquals("1<2", PlayerInput.stripSectionSigns("1<2"));
        assertEquals("<- back", PlayerInput.stripSectionSigns("<- back"));
        assertEquals("< что-то >", PlayerInput.stripSectionSigns("< что-то >"));
        assertEquals("left", PlayerInput.stripSectionSigns("<<red>red>left"));
        assertEquals("", PlayerInput.stripSectionSigns("&#<red>FF0000"));
        assertEquals("{\"text\":\"Hi\"}", PlayerInput.stripSectionSigns("{\"text\":\"Hi\"}"));
        assertEquals("<3>", PlayerInput.stripSectionSigns("<3>"));
        assertEquals("Hi <red there", PlayerInput.stripSectionSigns("Hi <red there"));
        assertEquals("<red>Hi</red>", PlayerInput.sanitize("<red>Hi</red>"));
        assertEquals("&#FF0000Hi", PlayerInput.sanitize("&#FF0000Hi"));
    }

    @Test
    void quotedTagArgumentsAndInteractiveJsonAreStrippedWithoutManglingBraceText() {
        assertEquals("tip", PlayerInput.stripSectionSigns("<hover:show_text:'hello>world'>tip</hover>"));
        assertEquals("tip", PlayerInput.stripSectionSigns("<hover:show_text:\"hello>world\">tip</hover>"));
        assertEquals("tip", PlayerInput.stripSectionSigns("<hover:show_text:'it\\'s > here'>tip</hover>"));
        assertEquals("tip", PlayerInput.stripSectionSigns("<hover:show_text:'<red>hi>there'>tip</hover>"));
        assertEquals("x", PlayerInput.stripSectionSigns("<hover:show_text:\"say '>' now\">x</hover>"));

        String click = "{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}";
        String array = "[\"\",{\"text\":\"Hi\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/say pwned\"}}]";
        String hover = "{\"text\":\"tip\",\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"secret\"}}";
        String modern = "{\"text\":\"x\",\"click_event\":{\"action\":\"run_command\",\"command\":\"/op me\"}}";
        String insertion = "{\"text\":\"Hi\",\"insertion\":\"/op me\"}";
        String extra = "{\"text\":\"Hello \",\"extra\":[{\"text\":\"world\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}]}";
        String pretty = "{\n  \"text\": \"Hi\",\n  \"clickEvent\": { \"action\": \"run_command\", \"value\": \"/op me\" }\n}";
        String escaped = "{\"text\":\"H\\u0069\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}";

        assertEquals("x", PlayerInput.stripSectionSigns(click));
        assertEquals("Hi", PlayerInput.stripSectionSigns(array));
        assertEquals("tip", PlayerInput.stripSectionSigns(hover));
        assertEquals("x", PlayerInput.stripSectionSigns(modern));
        assertEquals("Hi", PlayerInput.stripSectionSigns(insertion));
        assertEquals("Hello world", PlayerInput.stripSectionSigns(extra));
        assertEquals("Hi", PlayerInput.stripSectionSigns(pretty));
        assertEquals("Hi", PlayerInput.stripSectionSigns(escaped));
        assertEquals("Hello x world", PlayerInput.stripSectionSigns("Hello " + click + " world"));
        assertEquals("code { return 1; } and <3 and x",
                PlayerInput.stripSectionSigns("code { return 1; } and <3 and " + click));
        assertEquals("{\"text\":\"Hi\"}", PlayerInput.stripSectionSigns("{\"text\":\"Hi\"}"));
        assertEquals("{\"text\":\"Hi\",\"color\":\"red\"}", PlayerInput.stripSectionSigns("{\"text\":\"Hi\",\"color\":\"red\"}"));
        assertEquals("{^_^}", PlayerInput.stripSectionSigns("{^_^}"));
        assertEquals("{ :D }", PlayerInput.stripSectionSigns("{ :D }"));
        assertEquals("if (x) { return 1; }", PlayerInput.stripSectionSigns("if (x) { return 1; }"));
        assertEquals("[1, 2, 3]", PlayerInput.stripSectionSigns("[1, 2, 3]"));
        String wrapped = "{\"note\":{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}}";
        assertEquals("x", PlayerInput.stripSectionSigns(wrapped));
        assertEquals("hello x world", PlayerInput.stripSectionSigns("hello " + wrapped + " world"));
        assertEquals("", PlayerInput.stripSectionSigns(
                "{\"note\":{\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}}"));
        assertEquals("seex", PlayerInput.stripSectionSigns(
                "{\"note\":\"see\",\"child\":{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}}"));
        assertEquals("{\"note\":\"just text\"}", PlayerInput.stripSectionSigns("{\"note\":\"just text\"}"));
        assertEquals("rock & stone {^_^}", PlayerInput.stripSectionSigns("rock & stone {^_^}"));
        assertEquals("<3", PlayerInput.stripSectionSigns("<3"));
        assertEquals("x < y", PlayerInput.stripSectionSigns("x < y"));
        assertEquals("1<2", PlayerInput.stripSectionSigns("1<2"));
        assertEquals("<- back", PlayerInput.stripSectionSigns("<- back"));
        assertEquals(click, PlayerInput.stripSectionSigns(click, true));
        assertEquals("<hover:show_text:'hello>world'>tip</hover>",
                PlayerInput.stripSectionSigns("<hover:show_text:'hello>world'>tip</hover>", true));

        assertTrue(PlayerInput.emptiedByMarkup("<key:key.jump>", false));
        assertTrue(PlayerInput.emptiedByMarkup("&#<red>FF0000", false));
        assertFalse(PlayerInput.emptiedByMarkup("&c§l", false));
        assertFalse(PlayerInput.emptiedByMarkup("<key:key.jump>", true));
        assertFalse(PlayerInput.emptiedByMarkup("Hello", false));
        assertFalse(PlayerInput.emptiedByMarkup("{^_^}", false));
    }

    @Test
    void allowMarkupKeepsTagsAndHexButAlwaysStripsLegacyCodes() {
        String raw = "§c&#ff00AA<click:run_command:'/op Steve'>Hi</click>&r&x&f&f&0&0&0&0";
        assertEquals("&#ff00AA<click:run_command:'/op Steve'>Hi</click>", PlayerInput.stripSectionSigns(raw, true));
        assertEquals("Hi", PlayerInput.stripSectionSigns(raw, false));
        assertEquals("&#ff00AA<RED>Hi</RED>", PlayerInput.stripSectionSigns("&#ff00AA<RED>Hi</RED>&l", true));
        assertEquals("Hi", PlayerInput.stripSectionSigns("&#ff00AA<RED>Hi</RED>&l", false));
        assertEquals("<#ff00AA>Hi</#ff00AA>", PlayerInput.stripSectionSigns("<#ff00AA>Hi</#ff00AA>", true));
        assertEquals("rock & stone", PlayerInput.stripSectionSigns("rock & stone", true));
        assertEquals("<3", PlayerInput.stripSectionSigns("<3", true));
    }

    @Test
    void cachedAndPooledAnswersFromOlderJarsAreStrippedOnRead() throws Exception {
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10);
        cache.put("old", "<click:run_command:/say pwned>CLICK</click>");
        cache.put("hex", "&#FF0000[ADMIN]&r Test");
        cache.put("json", "{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}");
        cache.put("quoted", "<hover:show_text:'hello>world'>tip</hover>");
        assertEquals("CLICK", cache.get("old").orElseThrow());
        assertEquals("[ADMIN] Test", cache.get("hex").orElseThrow());
        assertEquals("x", cache.get("json").orElseThrow());
        assertEquals("tip", cache.get("quoted").orElseThrow());

        AiCache kept = new AiCache(Duration.ofMinutes(5), 10, true);
        kept.put("old", "<red>Hi</red>");
        kept.put("legacy", "&cHi");
        assertEquals("<red>Hi</red>", kept.get("old").orElseThrow());
        assertEquals("Hi", kept.get("legacy").orElseThrow());

        AiPool pool = new AiPool();
        assertTrue(pool.add("greet", "<red>Hi</red>"));
        assertEquals("Hi", pool.poll("greet").orElseThrow());

        Path dir = Files.createTempDirectory("nexusai-markup-pool");
        Path file = dir.resolve("pool.yml");
        Files.writeString(file, """
                config-version: 1
                pools:
                  - prompt: "greet"
                    answers:
                      - "<click:run_command:/say pwned>CLICK</click>"
                      - "&#FF0000[ADMIN]&r Test"
                      - "{\\"text\\":\\"x\\",\\"clickEvent\\":{\\"action\\":\\"run_command\\",\\"value\\":\\"/op me\\"}}"
                """);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file.toFile(), scheduler, Duration.ofMillis(50), Logger.getLogger("markup-pool"), true);
            AiPool loaded = new AiPool();
            store.load(loaded, Map.of("greet", 5));
            assertEquals(List.of("CLICK", "[ADMIN] Test", "x"), loaded.copy("greet"));

            PoolStore open = new PoolStore(file.toFile(), scheduler, Duration.ofMillis(50), Logger.getLogger("markup-pool"), true, true);
            AiPool passed = new AiPool(true);
            open.load(passed, Map.of("greet", 5));
            assertEquals(
                    List.of(
                            "<click:run_command:/say pwned>CLICK</click>",
                            "&#FF0000[ADMIN] Test",
                            "{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op me\"}}"),
                    passed.copy("greet"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void talkAndTestRenderSanitizedRepliesAsPlainText() throws Exception {
        YamlConfiguration en = new YamlConfiguration();
        en.load(Path.of("src/main/resources/lang/en.yml").toFile());
        MessageService messages = MessageService.forTest(en, en, "en");

        String admin = PlayerInput.stripSectionSigns("&#FF0000[ADMIN]&r Test");
        String click = PlayerInput.stripSectionSigns("<click:run_command:/say pwned>CLICK</click>");
        assertEquals("[ADMIN] Test", admin);
        assertEquals("CLICK", click);

        Component talkAdmin = messages.component("talk.reply", Map.of("character", "npc", "reply", admin));
        Component talkClick = messages.component("talk.reply", Map.of("character", "npc", "reply", click));
        Component testAdmin = messages.component("command.test-ok", Map.of("latency_ms", "1", "answer", admin));
        Component testClick = messages.component("command.test-ok", Map.of("latency_ms", "1", "answer", click));

        assertPlainReply(talkAdmin, "[ADMIN] Test");
        assertPlainReply(talkClick, "CLICK");
        assertPlainReply(testAdmin, "[ADMIN] Test");
        assertPlainReply(testClick, "CLICK");

        String unsafe = "<click:run_command:/say pwned>CLICK</click>";
        Component literal = messages.component("talk.reply", Map.of("character", "npc", "reply", unsafe));
        assertNoInteraction(literal);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(literal).contains(unsafe));
        assertNotNull(clickTarget(MiniMessage.miniMessage().deserialize(unsafe)));

        Component colored = messages.component("talk.reply", Map.of("character", "npc", "reply", "&#FF0000[ADMIN]&r Test"));
        TextComponent rawNode = findContent(colored, "&#FF0000[ADMIN]&r Test");
        assertNotNull(rawNode);
        assertEquals(NamedTextColor.WHITE, rawNode.color());
        assertNull(rawNode.clickEvent());
    }

    private static void assertPlainReply(Component rendered, String reply) {
        assertNoInteraction(rendered);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertTrue(plain.contains(reply), plain);
        assertFalse(plain.contains("&#"));
        assertFalse(plain.contains("&r"));
        assertFalse(plain.contains("<"));
        assertFalse(plain.contains(">"));
        TextComponent node = findContent(rendered, reply);
        assertNotNull(node, plain);
        TextColor color = node.color();
        assertNotNull(color);
        assertEquals(NamedTextColor.WHITE, color);
        assertFalse(color.value() == 0xFF0000);
        assertFalse(NamedTextColor.RED.equals(color));
    }

    private static void assertNoInteraction(Component component) {
        assertNull(component.clickEvent());
        assertNull(component.hoverEvent());
        for (Component child : component.children()) {
            assertNoInteraction(child);
        }
    }

    private static ClickEvent clickTarget(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent found = clickTarget(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static TextComponent findContent(Component component, String exact) {
        if (component instanceof TextComponent text && exact.equals(text.content())) {
            return text;
        }
        for (Component child : component.children()) {
            TextComponent found = findContent(child, exact);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
