package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.api.NexusAIApi;
import io.github.neareststep.nexusai.api.PromptDefinition;
import io.github.neareststep.nexusai.dialogue.CharacterAction;
import io.github.neareststep.nexusai.dialogue.DialogueProfile;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import io.github.neareststep.nexusai.prompt.PromptContext;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPromptRegistryTest {

    private final ApiPromptRegistry registry = ApiPromptRegistry.get();

    @AfterEach
    void clearRegistry() {
        registry.clear();
    }

    @Test
    void namespaceIsLowerCaseAndReplacesOtherCharacters() {
        assertEquals("quests", ApiPromptRegistry.namespace("Quests"));
        assertEquals("my_plugin", ApiPromptRegistry.namespace("My Plugin"));
        assertEquals("my_plugin", ApiPromptRegistry.namespace("my_plugin"));
        assertEquals("already-ok_1", ApiPromptRegistry.namespace("Already-OK 1"));
        assertEquals("quests_", ApiPromptRegistry.namespace("Quests!"));
        assertEquals("______", ApiPromptRegistry.namespace("Квесты"));
        assertThrows(IllegalArgumentException.class, () -> ApiPromptRegistry.namespace("  "));
        assertThrows(IllegalArgumentException.class, () -> ApiPromptRegistry.namespace(null));
    }

    @Test
    void registerReplaceUnregisterAndListIds() {
        Plugin quests = plugin("Quests", true);
        Object schema = Map.of("type", "object");
        NexusAIApi.registerPrompt(quests, "intro", PromptDefinition.builder("one")
                .format("SHORT")
                .systemPrompt("Be brief")
                .temperature(0.2)
                .maxTokens(120)
                .model("quest-model")
                .ttl(Duration.ofMinutes(30))
                .fallback("A new quest awaits.")
                .vars(Map.of("tone", "calm"))
                .knowledge(List.of("lore", "lore"))
                .schema(schema)
                .build());
        assertEquals(List.of("quests:intro"), NexusAIApi.registeredPromptIds(quests));
        ApiPromptRegistry.Effective first = registry.effective(PromptCatalog.empty(), "quests:intro").orElseThrow();
        assertEquals("one", first.prompt().template());
        assertEquals("short", first.prompt().format());
        assertEquals("Be brief", first.prompt().overrides().systemPrompt(""));
        assertEquals(0.2d, first.prompt().overrides().temperature(null));
        assertEquals(120, first.prompt().overrides().maxTokens(0));
        assertEquals("quest-model", first.prompt().overrides().model("gpt-4o-mini"));
        assertEquals(Duration.ofMinutes(30), first.prompt().ttl());
        assertEquals("A new quest awaits.", first.prompt().fallback());
        assertEquals("calm", first.prompt().vars().get("tone"));
        assertEquals(List.of("lore"), first.prompt().knowledge());
        assertSame(schema, first.schema());
        assertFalse(first.fileOverride());
        assertTrue(first.prompt().actions().isEmpty());
        assertFalse(first.prompt().dialogue().defined());
        assertFalse(first.prompt().context().active());

        registry.register(quests, "reward", PromptDefinition.builder("gold").build());
        registry.register(quests, "intro", PromptDefinition.builder("two").build());
        assertEquals(List.of("quests:intro", "quests:reward"), registry.ids(quests));
        assertEquals("two", registry.effective(PromptCatalog.empty(), "quests:intro").orElseThrow().prompt().template());
        assertNull(registry.effective(PromptCatalog.empty(), "quests:intro").orElseThrow().schema());

        assertTrue(NexusAIApi.unregisterPrompt(quests, "intro"));
        assertFalse(NexusAIApi.unregisterPrompt(quests, "intro"));
        assertEquals(List.of("quests:reward"), NexusAIApi.registeredPromptIds(quests));
        assertTrue(registry.effective(PromptCatalog.empty(), "quests:intro").isEmpty());
    }

    @Test
    void localIdMustMatchThePattern() {
        Plugin quests = plugin("Quests", true);
        PromptDefinition definition = PromptDefinition.builder("hello").build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(quests, null, definition));
        assertThrows(IllegalArgumentException.class, () -> registry.register(quests, "", definition));
        assertThrows(IllegalArgumentException.class, () -> registry.register(quests, "Intro", definition));
        assertThrows(IllegalArgumentException.class, () -> registry.register(quests, "has space", definition));
        assertThrows(IllegalArgumentException.class, () -> registry.register(quests, "a:b", definition));
        assertThrows(IllegalArgumentException.class, () -> registry.register(quests, "a".repeat(65), definition));
        registry.register(quests, "a".repeat(64), definition);
        assertEquals("quests:" + "a".repeat(64), registry.ids(quests).getFirst());
        assertThrows(IllegalArgumentException.class, () -> registry.register(null, "intro", definition));
        assertThrows(IllegalArgumentException.class, () -> registry.register(quests, "intro", null));
        assertThrows(IllegalArgumentException.class, () -> NexusAIApi.registerPrompt(null, "intro", definition));
        assertFalse(NexusAIApi.unregisterPrompt(null, "intro"));
        assertEquals(List.of(), NexusAIApi.registeredPromptIds(null));
        assertFalse(registry.unregister(quests, "Missing"));
    }

    @Test
    void definitionRejectsEmptyTextPapiVarsAndOutOfRangeNumbers() {
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("  \n").build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").format(" ").build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").temperature(2.1).build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").temperature(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").maxTokens(0).build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").maxTokens(32_769).build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").ttl(Duration.ofMillis(999)).build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").model(" ").build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").vars(null).build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").vars(Map.of("biome", "%player_biome%")).build());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("hi").knowledge(List.of("lore.md")).build());
        PromptDefinition built = PromptDefinition.builder("hi\n").temperature(2).maxTokens(32_768).ttl(Duration.ofSeconds(1)).build();
        assertEquals("hi", built.text());
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("tone", "calm");
        PromptDefinition withVars = PromptDefinition.builder("hi").vars(vars).build();
        vars.put("tone", "mutated");
        assertEquals("calm", withVars.vars().get("tone"));
        assertThrows(UnsupportedOperationException.class, () -> withVars.vars().put("extra", "no"));
    }

    @Test
    void adminOnlyFieldsAreRejectedAndAbsentFromTheBuilder() throws Exception {
        Set<String> names = new HashSet<>();
        for (Method method : PromptDefinition.Builder.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        assertFalse(names.contains("actions"));
        assertFalse(names.contains("dialogue"));
        assertFalse(names.contains("context"));

        NamedPrompt actions = codeShaped("actions", List.of(new CharacterAction(
                "give_iron", "Give iron", "give {player} iron_ingot 1", false, 0, 0, null)), DialogueProfile.absent(), PromptContext.none());
        IllegalArgumentException actionError = assertThrows(IllegalArgumentException.class,
                () -> ApiPromptRegistry.rejectAdminOnlyFields(actions));
        assertTrue(actionError.getMessage().contains("actions"), actionError.getMessage());
        assertTrue(actionError.getMessage().contains("prompts.yml"), actionError.getMessage());

        NamedPrompt dialogue = codeShaped("dialogue", List.of(), new DialogueProfile(true, "Hello", null, null, null, null, null), PromptContext.none());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ApiPromptRegistry.rejectAdminOnlyFields(dialogue))
                .getMessage().contains("dialogue"));

        NamedPrompt context = codeShaped("context", List.of(), DialogueProfile.absent(), PromptContext.all());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ApiPromptRegistry.rejectAdminOnlyFields(context))
                .getMessage().contains("context"));

        ApiPromptRegistry.rejectAdminOnlyFields(codeShaped("plain", List.of(), DialogueProfile.absent(), PromptContext.none()));
    }

    @Test
    void anotherPluginCannotTakeTheSameNamespace() {
        Plugin spaced = plugin("My Plugin", true);
        Plugin plain = plugin("my_plugin", true);
        registry.register(spaced, "intro", PromptDefinition.builder("first").build());
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> registry.register(plain, "other", PromptDefinition.builder("second").build()));
        assertTrue(conflict.getMessage().contains("My Plugin"), conflict.getMessage());
        assertTrue(conflict.getMessage().contains("my_plugin"), conflict.getMessage());
        assertEquals(List.of("my_plugin:intro"), registry.ids(spaced));
        assertTrue(registry.ids(plain).isEmpty());

        assertFalse(registry.unregister(plain, "intro"));
        assertEquals("first", registry.effective(PromptCatalog.empty(), "my_plugin:intro").orElseThrow().prompt().template());

        assertTrue(registry.unregister(spaced, "intro"));
        registry.register(plain, "other", PromptDefinition.builder("second").build());
        assertEquals(List.of("my_plugin:other"), registry.ids(plain));
    }

    @Test
    void promptsYmlReplacesThePromptExceptTheCodeSchema() {
        Plugin quests = plugin("Quests", true);
        Object schema = new Object();
        registry.register(quests, "intro", PromptDefinition.builder("from code")
                .fallback("code fallback")
                .format("chat")
                .schema(schema)
                .build());
        PromptCatalog file = PromptCatalog.parse("""
                "quests:intro":
                  prompt: "from file"
                  fallback: "file fallback"
                  context: all
                  actions:
                    - name: give_iron
                      description: Give the player one iron ingot.
                      command: "give {player} iron_ingot 1"
                survival_tips: "old id"
                """).catalog();
        ApiPromptRegistry.Effective overridden = registry.effective(file, "quests:intro").orElseThrow();
        assertTrue(overridden.fileOverride());
        assertEquals("from file", overridden.prompt().template());
        assertEquals("file fallback", overridden.prompt().fallback());
        assertTrue(overridden.prompt().context().includesAll());
        assertEquals(1, overridden.prompt().actions().size());
        assertNull(overridden.prompt().format());
        assertSame(schema, overridden.schema());

        PromptCatalog view = file.overlayRegistered(registry.namedPrompts());
        assertEquals("from file", view.find("quests:intro").orElseThrow().template());
        assertEquals("old id", view.find("survival_tips").orElseThrow().template());
        assertTrue(view.ids().contains("quests:intro"));
        assertTrue(view.ids().contains("survival_tips"));

        PromptCatalog withoutFile = PromptCatalog.parse("survival_tips: \"old id\"\n").catalog();
        ApiPromptRegistry.Effective restored = registry.effective(withoutFile, "quests:intro").orElseThrow();
        assertFalse(restored.fileOverride());
        assertEquals("from code", restored.prompt().template());
        assertEquals("code fallback", restored.prompt().fallback());
        assertFalse(restored.prompt().context().active());
        assertSame(schema, restored.schema());
        assertEquals("quests:intro (Quests)", registry.summary());
    }

    @Test
    void fileOnlyPromptHasNoCodeSchema() {
        PromptCatalog file = PromptCatalog.parse("ping: \"pong\"\n").catalog();
        ApiPromptRegistry.Effective fileOnly = registry.effective(file, "ping").orElseThrow();
        assertFalse(fileOnly.fileOverride());
        assertNull(fileOnly.schema());
        assertEquals("pong", fileOnly.prompt().template());
        assertTrue(registry.effective(file, "missing").isEmpty());
        assertEquals("", registry.summary());
    }

    @Test
    void disablingTheOwnerRemovesItsPromptsWithoutReload() throws Exception {
        Plugin quests = plugin("Quests", true);
        Plugin shop = plugin("Shop", true);
        registry.register(quests, "intro", PromptDefinition.builder("quest").build());
        registry.register(shop, "price", PromptDefinition.builder("price").build());
        assertEquals("quests:intro (Quests), shop:price (Shop)", registry.summary());

        ApiPromptRegistry.DisableListener listener = new ApiPromptRegistry.DisableListener();
        assertTrue(listener.getClass().getMethod("onPluginDisable", PluginDisableEvent.class)
                .isAnnotationPresent(EventHandler.class));
        listener.onPluginDisable(null);
        listener.forget(null);
        listener.forget(quests);
        assertTrue(registry.ids(quests).isEmpty());
        assertEquals(List.of("shop:price"), registry.ids(shop));
        assertEquals("shop:price (Shop)", registry.summary());
        assertTrue(registry.effective(PromptCatalog.empty(), "quests:intro").isEmpty());

        registry.register(plugin("My Plugin", true), "intro", PromptDefinition.builder("taken").build());
        registry.register(quests, "intro", PromptDefinition.builder("again").build());
        assertEquals("again", registry.effective(PromptCatalog.empty(), "quests:intro").orElseThrow().prompt().template());
    }

    @Test
    void retainEnabledDropsPluginsThatAreOff() {
        Plugin off = plugin("Off", false);
        Plugin on = plugin("On", true);
        registry.register(off, "a", PromptDefinition.builder("gone").build());
        registry.register(on, "b", PromptDefinition.builder("stay").build());
        registry.retainEnabled();
        assertTrue(registry.ids(off).isEmpty());
        assertEquals(List.of("on:b"), registry.ids(on));
        registry.register(plugin("off", true), "a", PromptDefinition.builder("free").build());
        assertEquals("free", registry.effective(PromptCatalog.empty(), "off:a").orElseThrow().prompt().template());
    }

    @Test
    void reloadLeavesRegisteredPromptsInPlace(@TempDir Path dir) throws Exception {
        Plugin quests = plugin("Quests", true);
        registry.register(quests, "intro", PromptDefinition.builder("kept").build());
        Path prompts = dir.resolve("prompts.yml");
        Files.writeString(prompts, "ping: \"pong\"\n");
        byte[] before = Files.readAllBytes(prompts);
        PromptCatalog reloaded = PromptCatalog.parse(Files.readString(prompts)).catalog();
        assertEquals("kept", registry.effective(reloaded, "quests:intro").orElseThrow().prompt().template());
        assertEquals("pong", registry.effective(reloaded, "ping").orElseThrow().prompt().template());
        assertArrayEquals(before, Files.readAllBytes(prompts));
        try (var listed = Files.list(dir)) {
            assertEquals(1, listed.count());
        }
    }

    @Test
    void registerAndUnregisterAreThreadSafe() throws Exception {
        Plugin owner = plugin("Race", true);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            tasks.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int round = 0; round < 40; round++) {
                    registry.register(owner, "p" + n, PromptDefinition.builder("t" + n + "-" + round).build());
                    registry.ids(owner);
                    registry.summary();
                    registry.namedPrompts();
                    if ((round + n) % 2 == 0) {
                        registry.unregister(owner, "p" + n);
                    }
                }
                registry.register(owner, "p" + n, PromptDefinition.builder("final" + n).build());
                return null;
            }));
        }
        for (Future<?> task : tasks) {
            task.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(threads, registry.ids(owner).size());
        for (int i = 0; i < threads; i++) {
            assertEquals("final" + i,
                    registry.effective(PromptCatalog.empty(), "race:p" + i).orElseThrow().prompt().template());
        }
    }

    @Test
    void namespaceConflictIsExclusiveUnderContention() throws Exception {
        Plugin spaced = plugin("My Plugin", true);
        Plugin plain = plugin("my_plugin", true);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Plugin owner = i % 2 == 0 ? spaced : plain;
            String local = "n" + i;
            tasks.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    registry.register(owner, local, PromptDefinition.builder("x").build());
                    won.incrementAndGet();
                } catch (IllegalStateException conflict) {
                    lost.incrementAndGet();
                    assertTrue(conflict.getMessage().contains("My Plugin"), conflict.getMessage());
                    assertTrue(conflict.getMessage().contains("my_plugin"), conflict.getMessage());
                }
                return null;
            }));
        }
        for (Future<?> task : tasks) {
            task.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(threads, won.get() + lost.get());
        assertEquals(4, won.get());
        assertEquals(4, lost.get());
        boolean spacedWon = !registry.ids(spaced).isEmpty();
        boolean plainWon = !registry.ids(plain).isEmpty();
        assertTrue(spacedWon ^ plainWon);
    }

    private static NamedPrompt codeShaped(
            String id,
            List<CharacterAction> actions,
            DialogueProfile dialogue,
            PromptContext context
    ) {
        return new NamedPrompt(
                id,
                "text",
                Map.of(),
                null,
                null,
                null,
                io.github.neareststep.nexusai.config.GenerationOverrides.none(),
                null,
                List.of(),
                null,
                dialogue,
                actions,
                context);
    }

    private static Plugin plugin(String name, boolean enabled) {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger(name);
                    }
                    if ("isEnabled".equals(method.getName())) {
                        return enabled;
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
