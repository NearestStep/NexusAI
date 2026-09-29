package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextVariableTest {

    @Test
    void timeAndWeatherFormats() {
        assertEquals("day 06:00", GameClock.format(0));
        assertEquals("day 12:00", GameClock.format(6_000));
        assertEquals("night 18:00", GameClock.format(12_000));
        assertEquals("night 00:00", GameClock.format(18_000));
        assertEquals("clear", GameClock.weather(false, false));
        assertEquals("rain", GameClock.weather(true, false));
        assertEquals("thunder", GameClock.weather(true, true));
    }

    @Test
    void userVarsOverrideBuiltInsAndBuiltInsDoNotNeedPapi() {
        var prompt = PromptCatalog.parse("""
                greet:
                  prompt: "Hello {player} in {biome}"
                  vars:
                    biome: hub
                """).catalog().find("greet").orElseThrow();
        assertTrue(prompt.playerDependent());
        String rendered = prompt.render(template -> {
            throw new AssertionError(template);
        }, Map.of("player", "Steve", "biome", "plains", "weather", "rain"));
        assertEquals("Hello Steve in hub", rendered);
        assertTrue(PromptCatalog.parse("""
                where:
                  prompt: "You are in {world} during {time} with {weather}"
                """).catalog().find("where").orElseThrow().playerDependent());
        assertEquals("You are in {world}", ContextVariables.apply("You are in {world}", Set.of(), Map.of()));
        assertEquals("You are in lobby", ContextVariables.apply("You are in {world}", Set.of("player"), Map.of("world", "lobby")));
    }
}
