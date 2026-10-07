package io.github.neareststep.nexusai.dialogue;

import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.ai.ReasoningModels;
import io.github.neareststep.nexusai.config.FormatPresets;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.ModerationSettings;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.context.ContextBlock;
import io.github.neareststep.nexusai.context.ContextSanitizer;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.knowledge.KnowledgeComposer;
import io.github.neareststep.nexusai.placeholder.VarSubstitutor;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import io.github.neareststep.nexusai.prompt.ResolvedPrompt;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the UTF-8 JSON posted to {@code /chat/completions} on the 1.1.x paths.
 * Placeholder, pool, and prewarm go through {@link OpenAiProvider#buildBody}.
 * Talk and summaries go through {@link DialogueProtocol#requestJson}.
 * Moderation uses {@link OpenAiProvider#exchangeRaw}.
 * <p>
 * The fixtures are ordinary prose. They do not contain vendor-key shapes, so a later
 * change to {@code SecretMask} does not require editing these files.
 */
class HttpBodyGoldenTest {

    private static final String QUEUE_MODEL = "gpt-4o-mini";
    private static final String OTHER_QUEUE_MODEL = "llama-3.3-70b-versatile";
    private static final String SHOP = "Give the player one short shopping tip.";
    private static final Logger LOGGER = Logger.getLogger("golden-http");

    @Test
    void placeholderWithoutKnowledgeOrContextMatches11x() throws Exception {
        PluginConfig config = config();
        ResolvedPrompt resolved = ResolvedPrompt.literal(SHOP, config);
        byte[] body = postChat(config, resolved.text(), placeholderOverrides(config, resolved, KnowledgeBase.empty()),
                selectedModel(resolved.overrides(), QUEUE_MODEL), false);
        assertGolden("placeholder-simple.json", body);
    }

    @Test
    void placeholderBodyUsesTheQueueModelWhenThePromptDoesNotSetOne() throws Exception {
        PluginConfig config = config();
        ResolvedPrompt resolved = ResolvedPrompt.literal(SHOP, config);
        assertFalse(resolved.overrides().modelOverridden());
        byte[] body = postChat(config, resolved.text(), placeholderOverrides(config, resolved, KnowledgeBase.empty()),
                selectedModel(resolved.overrides(), OTHER_QUEUE_MODEL), false);
        assertGolden("placeholder-queue-model.json", body);
    }

    @Test
    void placeholderWithKnowledgeKeepsTheFullFileAndHtmlComment(@TempDir Path dir) throws Exception {
        PluginConfig config = config();
        KnowledgeBase knowledge = lore(dir);
        PromptCatalog catalog = prompts("""
                harbor:
                  prompt: "Where is the harbor?"
                  format: chat
                  model: llama-3.1-8b
                  system-prompt: "Be brief."
                  temperature: 0.5
                  max-tokens: 128
                  knowledge:
                    - lore
                """);
        ResolvedPrompt resolved = catalog.resolve("harbor", config, value -> value);
        KnowledgeComposer.Prepared prepared = KnowledgeComposer.prepare(
                resolved.overrides(), config.getSystemPrompt(), knowledge, resolved.knowledge());
        assertTrue(prepared.overrides().systemPrompt(config.getSystemPrompt()).contains("<!-- harbor note -->"));
        byte[] body = postChat(config, resolved.text(), prepared.overrides().withNoticeId("harbor"),
                selectedModel(prepared.overrides(), QUEUE_MODEL), false);
        assertGolden("placeholder-knowledge.json", body);
    }

    @Test
    void placeholderContextBlockAddsTheGuardAndDoesNotChangeTheModel() throws Exception {
        PluginConfig config = config();
        ResolvedPrompt resolved = ResolvedPrompt.literal(SHOP, config);
        String block = ContextSanitizer.block(
                List.of(new ContextSanitizer.Line("economy", 10, "~12k")), 600);
        String prompt = ContextBlock.appendUser(resolved.text(), block);
        byte[] body = postChat(config, prompt, placeholderOverrides(config, resolved, KnowledgeBase.empty()),
                selectedModel(resolved.overrides(), QUEUE_MODEL), false);
        assertGolden("placeholder-context.json", body);
    }

    @Test
    void poolSendsVarRulesAfterTheResolvedText() throws Exception {
        PluginConfig config = config();
        PromptCatalog catalog = prompts("""
                welcome:
                  prompt: "Write a one-line welcome for {player}."
                  format: chat
                  system-prompt: "Be a herald."
                  temperature: 0
                  max-tokens: 64
                  vars:
                    player: traveler
                """);
        NamedPrompt named = catalog.find("welcome").orElseThrow();
        String poolKey = catalog.staticText("welcome");
        assertNotNull(poolKey);
        String httpPrompt = VarSubstitutor.appendVarsRules(poolKey, named.vars());
        GenerationOverrides overrides = poolOverrides(config, named);
        byte[] body = postChat(config, httpPrompt, overrides.withNoticeId("welcome"),
                selectedModel(overrides, QUEUE_MODEL), false);
        assertGolden("pool-vars.json", body);
    }

    @Test
    void prewarmNamedPromptSendsKnowledgeBeforeTheFormatInstruction(@TempDir Path dir) throws Exception {
        PluginConfig config = config();
        KnowledgeBase knowledge = lore(dir);
        PromptCatalog catalog = prompts("""
                harbor_tip:
                  prompt: "Name one landmark in the harbor."
                  format: hologram
                  system-prompt: "Be brief."
                  max-tokens: 64
                  knowledge:
                    - lore
                """);
        NamedPrompt named = catalog.find("harbor_tip").orElseThrow();
        String text = named.render(value -> value);
        String format = config.normalizeFormat(named.format());
        GenerationOverrides overrides = named.overrides().withFormat(format);
        KnowledgeComposer.Prepared prepared = KnowledgeComposer.prepare(
                overrides, config.getSystemPrompt(), knowledge, named.knowledge());
        byte[] body = postChat(config, text, prepared.overrides().withNoticeId("harbor_tip"),
                selectedModel(prepared.overrides(), QUEUE_MODEL), false);
        assertGolden("prewarm-knowledge.json", body);
    }

    @Test
    void prewarmLiteralPromptStaysASingleUserMessage() throws Exception {
        PluginConfig config = config();
        GenerationOverrides overrides = GenerationOverrides.none()
                .withFormat(config.defaultFormatId())
                .withNoticeId("Give one short server tip.");
        byte[] body = postChat(config, "Give one short server tip.", overrides, QUEUE_MODEL, false);
        assertGolden("prewarm-literal.json", body);
    }

    @Test
    void talkTurnWrapsThePlayerLineAndPutsTheGuardLast() throws Exception {
        PluginConfig config = config();
        String system = characterSystem(config);
        DialogueEngine.ModelCall call = captureTalk(system, "Where is the smithy?", List.of(), false);
        assertGolden("talk-turn.json", talkBytes(config, call, QUEUE_MODEL, List.of()));
    }

    @Test
    void talkTurnWithAToolSendsTheFixedFunctionSchema() throws Exception {
        PluginConfig config = config();
        String system = characterSystem(config);
        CharacterAction action = new CharacterAction(
                "give_iron",
                "Give the player one iron ingot.",
                "give iron_ingot",
                false,
                0,
                0,
                null);
        DialogueEngine.ModelCall call = captureTalk(system, "Where is the smithy?", List.of(action), false);
        assertEquals(List.of(action), call.tools());
        assertGolden("talk-tools.json", talkBytes(config, call, QUEUE_MODEL, call.tools()));
    }

    @Test
    void talkGreetingOmitsTheGuardWhenTheSheetHasNoPlayerSpan() throws Exception {
        PluginConfig config = config();
        String system = characterSystem(config);
        DialogueEngine.ModelCall call = captureTalk(system, "", List.of(), true);
        assertEquals(1, call.messages().size());
        assertFalse(call.messages().getFirst().text().contains(PlayerInput.OPEN));
        assertGolden("talk-greeting.json", talkBytes(config, call, QUEUE_MODEL, List.of()));
    }

    @Test
    void talkGreetingWithContextSplicesTheBlockBeforeTheFormatInstruction() throws Exception {
        PluginConfig config = config();
        NamedPrompt bram = bram();
        String instruction = DialogueService.formatInstruction(bram, config);
        String sheet = DialogueService.characterSystem(bram, config, null);
        String block = ContextSanitizer.block(
                List.of(new ContextSanitizer.Line("economy", 10, "~12k")), 600);
        String system = ContextBlock.spliceSystem(sheet, instruction, block);
        DialogueEngine.ModelCall call = captureTalk(system, "", List.of(), true);
        assertGolden("talk-greeting-context.json", talkBytes(config, call, QUEUE_MODEL, List.of()));
    }

    @Test
    void talkSummaryUsesTheFixedPromptAndWrapsTheFoldedLines() throws Exception {
        PluginConfig config = config();
        String payload = DialogueSummary.foldedText(
                "The player asked about the harbor.",
                List.of(
                        new TurnMemory.Line("user", "Where is the smithy?"),
                        new TurnMemory.Line("assistant", "Down the lane.")));
        String wrapped = PlayerInput.wrap(payload);
        GenerationOverrides overrides = GenerationOverrides.of(
                false, null, false, null, true, DialogueSettings.defaults().summaryMaxTokens());
        DialogueEngine.ModelCall call = new DialogueEngine.ModelCall(
                DialogueSummary.SYSTEM_PROMPT,
                List.of(new DialogueProtocol.MemoryLine("user", wrapped)),
                List.of(),
                overrides,
                FormatPresets.SIMPLE,
                UUID.randomUUID(),
                wrapped,
                DialogueEngine.CallKind.SUMMARY,
                "",
                "");
        assertGolden("talk-summary.json", talkBytes(config, call, QUEUE_MODEL, List.of()));
    }

    @Test
    void moderationSendsTemperatureZeroAndTheDefaultClassifier() throws Exception {
        PluginConfig config = config();
        ModerationSettings settings = ModerationSettings.defaults();
        String wrapped = PlayerInput.wrap("Can you help me find the market?");
        GenerationOverrides overrides = GenerationOverrides.of(
                true,
                settings.systemPrompt(),
                true,
                settings.temperature(),
                true,
                settings.maxTokens(),
                true,
                QUEUE_MODEL
        ).withFormat(FormatPresets.SIMPLE);
        byte[] body = postChat(config, wrapped, overrides, QUEUE_MODEL, true);
        assertGolden("moderation.json", body);
    }

    @Test
    void fixturesDoNotContainVendorKeyShapes() throws Exception {
        for (String name : List.of(
                "placeholder-simple.json",
                "placeholder-queue-model.json",
                "placeholder-knowledge.json",
                "placeholder-context.json",
                "pool-vars.json",
                "prewarm-knowledge.json",
                "prewarm-literal.json",
                "talk-turn.json",
                "talk-tools.json",
                "talk-greeting.json",
                "talk-greeting-context.json",
                "talk-summary.json",
                "moderation.json")) {
            String text = new String(resource(name), StandardCharsets.UTF_8);
            assertFalse(text.contains("sk-"), name);
            assertFalse(text.contains("gsk_"), name);
            assertFalse(text.contains("AIza"), name);
        }
    }

    /**
     * Same choice as {@code RoutingProvider} and {@code DialogueRouter}: a prompt {@code model:}
     * wins, otherwise the selected queue row is what the body names.
     */
    private static String selectedModel(GenerationOverrides overrides, String queueModel) {
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        return effective.modelOverridden() ? effective.model(queueModel) : queueModel;
    }

    private static GenerationOverrides placeholderOverrides(
            PluginConfig config,
            ResolvedPrompt resolved,
            KnowledgeBase knowledge
    ) {
        return KnowledgeComposer.prepare(
                resolved.overrides(), config.getSystemPrompt(), knowledge, resolved.knowledge()).overrides();
    }

    private static GenerationOverrides poolOverrides(PluginConfig config, NamedPrompt named) {
        GenerationOverrides merged = named.overrides().withFormat(config.normalizeFormat(named.format()));
        return KnowledgeComposer.prepare(
                merged, config.getSystemPrompt(), KnowledgeBase.empty(), named.knowledge()).overrides();
    }

    private static String characterSystem(PluginConfig config) {
        return DialogueService.characterSystem(bram(), config, null);
    }

    private static NamedPrompt bram() {
        PromptCatalog catalog = prompts("""
                bram:
                  prompt: "You are Bram, the harbor keeper."
                  format: chat
                """);
        return catalog.find("bram").orElseThrow();
    }

    private static DialogueEngine.ModelCall captureTalk(
            String system,
            String message,
            List<CharacterAction> actions,
            boolean greeting
    ) {
        List<DialogueEngine.ModelCall> calls = new ArrayList<>();
        DialogueEngine engine = new DialogueEngine(
                new MemoryStore(),
                new SessionBook(),
                new ActionGate(),
                new DialogueBudget(),
                new GreetingCache(),
                call -> {
                    calls.add(call);
                    return DialogueEngine.ModelReply.text("The smithy is down the lane.");
                },
                (id, action, command) -> "ran",
                ActionLog.noop(),
                ZoneId.of("UTC"));
        DialogueSettings settings = new DialogueSettings(
                true, 8, false, 8000, 0, 120, 8, 12, 0, 20, 200, true, 300, true, true, 1);
        engine.talk(new DialogueEngine.TalkRequest(
                UUID.randomUUID(),
                "Steve",
                "bram",
                greeting ? "" : message,
                false,
                false,
                false,
                system,
                "...",
                DialogueProfile.absent(),
                actions,
                settings,
                GenerationOverrides.none().withFormat("chat"),
                "chat",
                "world",
                0,
                64,
                0,
                ignored -> true,
                1_700_000_000_000L));
        assertEquals(1, calls.size(), "the golden turn is the first model call");
        return calls.getFirst();
    }

    private static byte[] talkBytes(
            PluginConfig config,
            DialogueEngine.ModelCall call,
            String queueModel,
            List<CharacterAction> tools
    ) throws Exception {
        GenerationOverrides overrides = call.overrides() == null ? GenerationOverrides.none() : call.overrides();
        String model = selectedModel(overrides, queueModel);
        DialogueProtocol.TokenBudget budget = DialogueProtocol.tokens(
                model, overrides.maxTokens(config.getMaxTokens()), config.getReasoningEffort());
        Double temperature = overrides.temperature(config.getTemperature());
        if (ReasoningModels.isReasoning(model) && ReasoningModels.usesCompletionTokenCap(model)) {
            temperature = null;
        }
        AtomicReference<byte[]> captured = new AtomicReference<>();
        HttpServer server = server(captured);
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            DialogueTransport transport = new DialogueTransport(() -> config, HttpClient.newHttpClient());
            transport.send(new DialogueTransport.Request(
                    root,
                    "test-key",
                    model,
                    call.system(),
                    call.messages(),
                    tools,
                    temperature,
                    budget.maxTokens(),
                    budget.maxCompletionTokens(),
                    budget.reasoningEffort(),
                    config.getReadTimeout()));
        } finally {
            server.stop(0);
        }
        assertNotNull(captured.get());
        return captured.get();
    }

    private static byte[] postChat(
            PluginConfig config,
            String prompt,
            GenerationOverrides overrides,
            String model,
            boolean raw
    ) throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        HttpServer server = server(captured);
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OpenAiProvider provider = new OpenAiProvider(config, executor, LOGGER);
            if (raw) {
                provider.exchangeRaw(prompt, overrides, root, "test-key", model);
            } else {
                provider.exchangeAsync(prompt, overrides, root, "test-key", model).join();
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
        assertNotNull(captured.get());
        return captured.get();
    }

    private static HttpServer server(AtomicReference<byte[]> captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private static void assertGolden(String name, byte[] actual) throws Exception {
        byte[] expected = resource(name);
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8), name);
        assertArrayEquals(expected, actual, name);
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = HttpBodyGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }

    private static KnowledgeBase lore(Path dir) throws Exception {
        Files.writeString(dir.resolve("lore.md"), "<!-- harbor note -->\nThe harbor is old.\n");
        return KnowledgeBase.load(dir, 6000, 4000, new ArrayList<>(), LOGGER);
    }

    private static PromptCatalog prompts(String yaml) {
        PromptCatalog.Parsed parsed = PromptCatalog.parse(yaml);
        assertTrue(parsed.valid(), parsed.error());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        return parsed.catalog();
    }

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", QUEUE_MODEL);
        yaml.set("api.key", "test-key");
        yaml.set("api.system-prompt", "");
        yaml.set("api.temperature", -1);
        yaml.set("api.max-tokens", 256);
        yaml.set("api.reasoning-effort", "low");
        yaml.set("formats.default", "simple");
        return new PluginConfig(yaml);
    }
}
