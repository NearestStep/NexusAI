package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.api.NexusAIApi;
import io.github.neareststep.nexusai.config.StartupBannerMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupBannerTest {

    private static final int PREFIX_COLS = "[HH:MM:SS INFO]: ".length();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static StartupBanner.Snapshot sample() {
        return new StartupBanner.Snapshot(
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
        List<String> body = StartupBanner.plainBody(StartupBannerMode.FULL, sample());
        assertEquals(8, body.size());
        assertEquals("█▄  █ █▀▀▀ ▀▄ ▄▀ █  █ █▀▀▀ ▄▀▀▄ ▀█▀", body.get(0));
        assertEquals("█ ▀▄█ █▀▀   ▄▀▄  █  █ ▀▀▀█ █▀▀█  █", body.get(1));
        assertEquals("▀   ▀ ▀▀▀▀ ▀   ▀ ▀▀▀▀ ▀▀▀▀ ▀  ▀ ▀▀▀", body.get(2));
        assertEquals("─".repeat(StartupBanner.MAX_CONTENT_COLS), body.get(3));
        assertEquals("Version 1.2.0  |  Paper 26.2  |  Java 25", body.get(4));
        assertEquals("Providers openrouter, groq  |  Model queue 3", body.get(5));
        assertEquals("API v3 + events  |  Quotas off  |  Knowledge keywords", body.get(6));
        assertEquals("Ready in 412 ms  |  github.com/NearestStep/NexusAI", body.get(7));

        List<Component> components = StartupBanner.components(StartupBannerMode.FULL, sample());
        assertEquals(9, components.size());
        assertEquals("", PLAIN.serialize(components.get(0)));
        assertEquals(StartupBanner.BRAND, components.get(1).color());
        assertEquals(NamedTextColor.DARK_GRAY, components.get(4).color());
        assertSummaryColors(components.get(5),
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE);

        for (String line : body) {
            assertTrue(line.length() <= StartupBanner.MAX_CONTENT_COLS, line);
            assertTrue(PREFIX_COLS + line.length() <= 70, line);
        }
    }

    @Test
    void summaryLineUsesGrayLabelsWhiteValuesAndDarkGrayPipes() {
        Component line = StartupBanner.summaryLine(
                new StartupBanner.Field("Version", "1.3.0"),
                new StartupBanner.Field("Paper", "26.2"),
                new StartupBanner.Field("Java", "25"));
        assertEquals("Version 1.3.0  |  Paper 26.2  |  Java 25", PLAIN.serialize(line));
        assertSummaryColors(line,
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE);
    }

    @Test
    void compactBannerIsTwoShortLinesWithSpacer() {
        List<Component> components = StartupBanner.components(StartupBannerMode.COMPACT, sample());
        assertEquals(3, components.size());
        assertEquals("", PLAIN.serialize(components.get(0)));
        List<String> body = StartupBanner.plainBody(StartupBannerMode.COMPACT, sample());
        assertEquals(2, body.size());
        assertEquals("NexusAI 1.2.0  |  Paper 26.2  |  Java 25", body.get(0));
        assertEquals("Ready in 412 ms  |  github.com/NearestStep/NexusAI", body.get(1));
        assertSummaryColors(components.get(1),
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE,
                NamedTextColor.DARK_GRAY,
                NamedTextColor.GRAY, NamedTextColor.WHITE);
        for (String line : body) {
            assertTrue(PREFIX_COLS + line.length() <= 70, line);
        }
    }

    @Test
    void providersLineTruncatesToContentBudget() {
        String line = StartupBanner.providersLine(
                List.of("openrouter", "groq", "cerebras", "deepseek", "ollama"),
                12);
        assertTrue(line.length() <= StartupBanner.MAX_CONTENT_COLS, line);
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
