package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.api.NexusAIApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleBannerTest {

    private static final int PREFIX_COLS = "[HH:MM:SS INFO]: ".length();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static ConsoleBanner.Snapshot sample() {
        return new ConsoleBanner.Snapshot(
                "1.2.0",
                "26.2",
                25,
                List.of("openrouter", "groq"),
                3,
                NexusAIApi.API_VERSION,
                false,
                KnowledgeSelect.KEYWORDS,
                412L);
    }

    @Test
    void fullBannerMatchesMockupSummaryAndStaysWithinSeventyColumns() {
        List<String> body = ConsoleBanner.plainBody(sample());
        assertEquals(8, body.size());
        assertEquals("█▄  █ █▀▀▀ ▀▄ ▄▀ █  █ █▀▀▀ ▄▀▀▄ ▀█▀", body.get(0));
        assertEquals("█ ▀▄█ █▀▀   ▄▀▄  █  █ ▀▀▀█ █▀▀█  █", body.get(1));
        assertEquals("▀   ▀ ▀▀▀▀ ▀   ▀ ▀▀▀▀ ▀▀▀▀ ▀  ▀ ▀▀▀", body.get(2));
        assertEquals("─".repeat(ConsoleBanner.MAX_CONTENT_COLS), body.get(3));
        assertEquals("Version 1.2.0  |  Paper 26.2  |  Java 25", body.get(4));
        assertEquals("Providers openrouter, groq  |  Model queue 3", body.get(5));
        assertEquals("API v3 + events  |  Quotas off  |  Knowledge keywords", body.get(6));
        assertEquals("Ready in 412 ms  |  github.com/NearestStep/NexusAI", body.get(7));

        List<Component> components = ConsoleBanner.components(sample());
        assertEquals(9, components.size());
        assertEquals("", PLAIN.serialize(components.get(0)));
        assertEquals(ConsoleBanner.BRAND, components.get(1).color());
        assertEquals(NamedTextColor.DARK_GRAY, components.get(4).color());
        assertSummaryColors(components.get(5),
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE);

        for (String line : body) {
            assertTrue(line.length() <= ConsoleBanner.MAX_CONTENT_COLS, line);
            assertTrue(PREFIX_COLS + line.length() <= 70, line);
        }
    }

    @Test
    void summaryLineUsesGrayLabelsWhiteValuesAndDarkGrayPipes() {
        Component line = ConsoleBanner.summaryLine(
                new ConsoleBanner.Field("Version", "1.3.0"),
                new ConsoleBanner.Field("Paper", "26.2"),
                new ConsoleBanner.Field("Java", "25"));
        assertEquals("Version 1.3.0  |  Paper 26.2  |  Java 25", PLAIN.serialize(line));
        assertSummaryColors(line,
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE);
    }

    /**
     * The banner is always the full brand banner. These are the exact components the 1.3.0
     * {@code full} mode produced (captured on b82fe43), so the console output stays byte for byte the same.
     */
    @Test
    void bannerIsTheApprovedFullBannerComponentForComponent() {
        ConsoleBanner.Snapshot snapshot = new ConsoleBanner.Snapshot(
                "1.3.0", "26.2", 25, List.of("openrouter", "groq"), 3,
                NexusAIApi.API_VERSION, false, KnowledgeSelect.KEYWORDS, 412L);
        List<String> expected = List.of(
                "\"\"",
                "{\"color\":\"#00C5C2\",\"text\":\"█▄  █ █▀▀▀ ▀▄ ▄▀ █  █ █▀▀▀ ▄▀▀▄ ▀█▀\"}",
                "{\"color\":\"#00C5C2\",\"text\":\"█ ▀▄█ █▀▀   ▄▀▄  █  █ ▀▀▀█ █▀▀█  █\"}",
                "{\"color\":\"#00C5C2\",\"text\":\"▀   ▀ ▀▀▀▀ ▀   ▀ ▀▀▀▀ ▀▀▀▀ ▀  ▀ ▀▀▀\"}",
                "{\"color\":\"dark_gray\",\"text\":\"" + "─".repeat(53) + "\"}",
                "{\"extra\":[{\"color\":\"gray\",\"text\":\"Version \"},{\"color\":\"white\",\"text\":\"1.3.0\"},"
                        + "{\"color\":\"dark_gray\",\"text\":\"  |  \"},{\"color\":\"gray\",\"text\":\"Paper \"},"
                        + "{\"color\":\"white\",\"text\":\"26.2\"},{\"color\":\"dark_gray\",\"text\":\"  |  \"},"
                        + "{\"color\":\"gray\",\"text\":\"Java \"},{\"color\":\"white\",\"text\":\"25\"}],\"text\":\"\"}",
                "{\"extra\":[{\"color\":\"gray\",\"text\":\"Providers \"},{\"color\":\"white\",\"text\":\"openrouter, groq\"},"
                        + "{\"color\":\"dark_gray\",\"text\":\"  |  \"},{\"color\":\"gray\",\"text\":\"Model queue \"},"
                        + "{\"color\":\"white\",\"text\":\"3\"}],\"text\":\"\"}",
                "{\"extra\":[{\"color\":\"gray\",\"text\":\"API \"},{\"color\":\"white\",\"text\":\"v3 + events\"},"
                        + "{\"color\":\"dark_gray\",\"text\":\"  |  \"},{\"color\":\"gray\",\"text\":\"Quotas \"},"
                        + "{\"color\":\"white\",\"text\":\"off\"},{\"color\":\"dark_gray\",\"text\":\"  |  \"},"
                        + "{\"color\":\"gray\",\"text\":\"Knowledge \"},{\"color\":\"white\",\"text\":\"keywords\"}],\"text\":\"\"}",
                "{\"extra\":[{\"color\":\"gray\",\"text\":\"Ready in \"},{\"color\":\"white\",\"text\":\"412 ms\"},"
                        + "{\"color\":\"dark_gray\",\"text\":\"  |  \"},"
                        + "{\"color\":\"white\",\"text\":\"github.com/NearestStep/NexusAI\"}],\"text\":\"\"}");
        List<String> actual = ConsoleBanner.components(snapshot).stream()
                .map(GsonComponentSerializer.gson()::serialize)
                .toList();
        assertEquals(expected, actual);
        for (Component line : ConsoleBanner.components(snapshot)) {
            assertTrue(!PLAIN.serialize(line).contains("[NexusAI]"), PLAIN.serialize(line));
        }
        assertEquals(List.of(), ConsoleBanner.components(null));
    }

    @Test
    void providersLineTruncatesToContentBudget() {
        String line = ConsoleBanner.providersLine(
                List.of("openrouter", "groq", "cerebras", "deepseek", "ollama"),
                12);
        assertTrue(line.length() <= ConsoleBanner.MAX_CONTENT_COLS, line);
        assertTrue(line.startsWith("Providers "));
        assertTrue(line.contains("Model queue 12"));
    }

    private static void assertSummaryColors(Component line, TextColor... expected) {
        List<TextColor> colors = new ArrayList<>();
        collectColors(line, colors);
        assertEquals(List.of(expected), colors);
    }

    private static void collectColors(Component component, List<TextColor> colors) {
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            colors.add(text.color());
        }
        for (Component child : component.children()) {
            collectColors(child, colors);
        }
    }
}
