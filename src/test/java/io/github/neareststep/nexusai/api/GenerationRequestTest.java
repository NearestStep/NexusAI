package io.github.neareststep.nexusai.api;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRequestTest {

    @Test
    void promptAndTemplateAreExclusive() {
        GenerationRequest named = GenerationRequest.prompt("intro").build();
        assertEquals("intro", named.promptId().orElseThrow());
        assertTrue(named.template().isEmpty());
        assertEquals(CacheMode.CACHED, named.cacheMode());
        assertEquals("", named.label());
        assertTrue(named.vars().isEmpty());
        assertTrue(named.playerId().isEmpty());
        assertTrue(named.knowledge().isEmpty());
        assertTrue(named.knowledgeQuery().isEmpty());

        GenerationRequest inline = GenerationRequest.template("Hello").label("quest-intro").build();
        assertTrue(inline.promptId().isEmpty());
        assertEquals("Hello", inline.template().orElseThrow());
        assertEquals("quest-intro", inline.label());
    }

    @Test
    void blankPromptAndTemplateAreRejected() {
        assertIllegal("promptId is empty", () -> GenerationRequest.prompt(null).build());
        assertIllegal("promptId is empty", () -> GenerationRequest.prompt("  ").build());
        assertIllegal("template is empty", () -> GenerationRequest.template(null).build());
        assertIllegal("template is empty", () -> GenerationRequest.template(" ").build());
    }

    @Test
    void variableNamesAndCountAreCheckedAtBuild() {
        assertIllegal("variable name must match [a-z0-9_]{1,32}", () -> GenerationRequest.template("Hi").var(null, "a").build());
        assertIllegal("variable name must match [a-z0-9_]{1,32}", () -> GenerationRequest.template("Hi").var("Bad", "a").build());
        assertIllegal("variable name must match [a-z0-9_]{1,32}", () -> GenerationRequest.template("Hi").var("a-b", "a").build());
        assertIllegal("variable name must match [a-z0-9_]{1,32}", () -> GenerationRequest.template("Hi").var("", "a").build());
        assertIllegal("variable name must match [a-z0-9_]{1,32}", () -> GenerationRequest.template("Hi").var("a".repeat(33), "a").build());
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 33; i++) {
            tooMany.put("v" + i, "x");
        }
        assertIllegal("at most 32 variables", () -> GenerationRequest.template("Hi").vars(tooMany).build());
        assertIllegal("vars are required", () -> GenerationRequest.template("Hi").vars(null).build());
    }

    @Test
    void varsAreCopiedAndNullBecomesEmpty() {
        Map<String, String> input = new HashMap<>();
        input.put("quest", null);
        GenerationRequest request = GenerationRequest.template("Hi {quest}").vars(input).var("zone", "north").build();
        input.put("quest", "mutated");
        input.put("extra", "nope");
        assertEquals("", request.vars().get("quest"));
        assertEquals("north", request.vars().get("zone"));
        assertFalse(request.vars().containsKey("extra"));
        assertThrows(UnsupportedOperationException.class, () -> request.vars().put("zone", "south"));
    }

    @Test
    void labelTtlTemperatureAndMaxTokens() {
        assertIllegal("label is required", () -> GenerationRequest.template("Hi").label(null).build());
        assertIllegal("label must match [A-Za-z0-9_.:-]{1,64}", () -> GenerationRequest.template("Hi").label("").build());
        assertIllegal("label must match [A-Za-z0-9_.:-]{1,64}", () -> GenerationRequest.template("Hi").label("has space").build());
        assertIllegal("label must match [A-Za-z0-9_.:-]{1,64}", () -> GenerationRequest.template("Hi").label("a".repeat(65)).build());
        GenerationRequest labeled = GenerationRequest.template("Hi").label("quest.intro:1").build();
        assertEquals("quest.intro:1", labeled.label());

        assertIllegal("ttl is required", () -> GenerationRequest.template("Hi").ttl(null).build());
        assertIllegal("ttl must be from 1 second to 24 hours", () -> GenerationRequest.template("Hi").ttl(Duration.ZERO).build());
        assertIllegal("ttl must be from 1 second to 24 hours", () -> GenerationRequest.template("Hi").ttl(Duration.ofMillis(999)).build());
        assertIllegal("ttl must be from 1 second to 24 hours", () -> GenerationRequest.template("Hi").ttl(Duration.ofHours(24).plusNanos(1)).build());
        GenerationRequest ttl = GenerationRequest.template("Hi").ttl(Duration.ofHours(24)).build();
        assertEquals(Duration.ofHours(24), ttl.ttl().orElseThrow());

        assertIllegal("temperature must be at most 2", () -> GenerationRequest.template("Hi").temperature(2.0001d).build());
        assertIllegal("temperature must be at most 2", () -> GenerationRequest.template("Hi").temperature(Double.NaN).build());
        assertIllegal("temperature must be at most 2", () -> GenerationRequest.template("Hi").temperature(Double.POSITIVE_INFINITY).build());
        GenerationRequest omitTemp = GenerationRequest.template("Hi").temperature(-1.0d).build();
        assertEquals(-1.0d, omitTemp.temperature().orElseThrow());
        GenerationRequest capped = GenerationRequest.template("Hi").temperature(2.0d).build();
        assertEquals(2.0d, capped.temperature().orElseThrow());

        assertIllegal("maxTokens must be at most 32768", () -> GenerationRequest.template("Hi").maxTokens(32_769).build());
        GenerationRequest omitTokens = GenerationRequest.template("Hi").maxTokens(0).build();
        assertEquals(0, omitTokens.maxTokens().orElseThrow());
        assertEquals(32_768, GenerationRequest.template("Hi").maxTokens(32_768).build().maxTokens().orElseThrow());
    }

    @Test
    void omitValuesAreNotEqualToUnset() {
        GenerationRequest unset = GenerationRequest.template("Hi").build();
        GenerationRequest omitTemp = GenerationRequest.template("Hi").temperature(-1.0d).build();
        GenerationRequest omitTokens = GenerationRequest.template("Hi").maxTokens(-1).build();
        GenerationRequest blankSystem = GenerationRequest.template("Hi").systemPrompt("").build();
        assertTrue(unset.temperature().isEmpty());
        assertTrue(unset.maxTokens().isEmpty());
        assertTrue(unset.systemPrompt().isEmpty());
        assertFalse(unset.equals(omitTemp));
        assertFalse(unset.equals(omitTokens));
        assertFalse(unset.equals(blankSystem));
        assertEquals(omitTemp, GenerationRequest.template("Hi").temperature(-1.0d).build());
    }

    @Test
    void nullAccessorsAndPlayerLastCallWins() {
        assertIllegal("player is required", () -> GenerationRequest.template("Hi").player(null).build());
        assertIllegal("playerId is required", () -> GenerationRequest.template("Hi").playerId(null).build());
        assertIllegal("cacheMode is required", () -> GenerationRequest.template("Hi").cacheMode(null).build());
        assertIllegal("model is required", () -> GenerationRequest.template("Hi").model(null).build());
        assertIllegal("model is empty", () -> GenerationRequest.template("Hi").model(" ").build());
        assertIllegal("format is required", () -> GenerationRequest.template("Hi").format(null).build());
        assertIllegal("format is empty", () -> GenerationRequest.template("Hi").format("").build());
        assertIllegal("systemPrompt is required", () -> GenerationRequest.template("Hi").systemPrompt(null).build());
        assertIllegal("fallback is required", () -> GenerationRequest.template("Hi").fallback(null).build());
        assertIllegal("knowledge is required", () -> GenerationRequest.template("Hi").knowledge(null).build());
        assertIllegal("knowledge name is required", () -> GenerationRequest.template("Hi").knowledge(java.util.Arrays.asList("lore", null)).build());
        assertIllegal("knowledgeQuery is required", () -> GenerationRequest.template("Hi").knowledgeQuery(null).build());

        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Player player = player(second);
        GenerationRequest entity = GenerationRequest.template("Hi").playerId(first).player(player).build();
        assertEquals(second, entity.playerId().orElseThrow());
        assertSame(player, GenerationRequestFacts.player(entity));

        GenerationRequest idOnly = GenerationRequest.template("Hi").player(player).playerId(first).build();
        assertEquals(first, idOnly.playerId().orElseThrow());
        assertEquals(null, GenerationRequestFacts.player(idOnly));
    }

    @Test
    void toStringDoesNotDumpVariableValuesOrTheTemplate() {
        GenerationRequest request = GenerationRequest.template("secret-template-body")
                .var("quest", "secret-var-value")
                .build();
        String text = request.toString();
        assertFalse(text.contains("secret-template-body"));
        assertFalse(text.contains("secret-var-value"));
        assertTrue(text.contains("vars=1"));
    }

    @Test
    void publicTypesHaveNoPublicConstructors() {
        assertNoPublicConstructor(GenerationRequest.class);
        assertNoPublicConstructor(GenerationResult.class);
        assertNoPublicConstructor(GenerationError.class);
        assertNoPublicConstructor(TokenUsage.class);
        assertSame(TokenUsage.none(), TokenUsage.none());
    }

    private static void assertIllegal(String message, org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, executable);
        assertEquals(message, error.getMessage());
    }

    private static void assertNoPublicConstructor(Class<?> type) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()), type.getSimpleName());
        }
    }

    private static Player player(UUID id) {
        AtomicReference<UUID> held = new AtomicReference<>(id);
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return held.get();
                    }
                    if ("getName".equals(method.getName())) {
                        return "Steve";
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
}
