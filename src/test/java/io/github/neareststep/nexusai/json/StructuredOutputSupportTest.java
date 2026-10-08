package io.github.neareststep.nexusai.json;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.dto.ChatCompletionRequest;
import io.github.neareststep.nexusai.api.JsonSchema;
import io.github.neareststep.nexusai.api.StructuredMode;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputSupportTest {

    private static final JsonSchema QUEST = JsonSchema.parse("""
            {"type":"object","additionalProperties":false,"required":["title","goal","reward"],
             "properties":{"title":{"type":"string","maxLength":40},"goal":{"type":"string","maxLength":200},
             "reward":{"type":"integer","minimum":1,"maximum":1000}}}
            """);

    @AfterEach
    void clearMemory() {
        StructuredOutputSupport.clear();
    }

    @Test
    void eachModeBuildsTheDocumentedBodyAndStrictFollowsTheSchema() {
        PluginConfig config = config();
        GenerationOverrides schemaCall = prepared("auto");
        ChatCompletionRequest schemaBody = OpenAiProvider.buildBody(config, "Invent a quest", schemaCall);
        Map<?, ?> format = (Map<?, ?>) schemaBody.getResponseFormat();
        assertEquals("json_schema", format.get("type"));
        Map<?, ?> inner = (Map<?, ?>) format.get("json_schema");
        assertEquals("nexusai_" + QUEST.hash(), inner.get("name"));
        assertEquals(Boolean.TRUE, inner.get("strict"));
        assertEquals(1024, schemaBody.getMaxTokens());
        String system = schemaBody.getMessages().get(0).getContent();
        assertTrue(system.startsWith(StructuredOutputSupport.INSTRUCTION_PREFIX + QUEST.json()));
        assertFalse(system.contains("Reply in 1 to 3 sentences"));

        GenerationOverrides objectCall = prepared("json-object");
        ChatCompletionRequest objectBody = OpenAiProvider.buildBody(config, "Invent a quest", objectCall);
        assertEquals(Map.of("type", "json_object"), objectBody.getResponseFormat());

        GenerationOverrides promptCall = prepared("prompt");
        ChatCompletionRequest promptBody = OpenAiProvider.buildBody(config, "Invent a quest", promptCall);
        assertNull(promptBody.getResponseFormat());
        assertTrue(promptBody.getMessages().get(0).getContent().contains(QUEST.json()));

        GenerationOverrides open = StructuredOutputSupport.call(GenerationOverrides.none().withFormat("chat"), openSchema());
        StructuredOutputSupport.prepare(open, "openai", "gpt-4o-mini", "json-schema");
        ChatCompletionRequest openBody = OpenAiProvider.buildBody(config, "Invent a quest", open);
        Map<?, ?> openFormat = (Map<?, ?>) openBody.getResponseFormat();
        Map<?, ?> openInner = (Map<?, ?>) openFormat.get("json_schema");
        assertEquals(Boolean.FALSE, openInner.get("strict"));
        assertFalse(openBody.getMessages().get(0).getContent().contains("Reply in 1 to 3 sentences"));
    }

    @Test
    void anOrdinaryBodyOmitsResponseFormatAndUsesTheGlobalCap() {
        PluginConfig config = config();
        ChatCompletionRequest body = OpenAiProvider.buildBody(config, "Say hello", GenerationOverrides.none());
        assertNull(body.getResponseFormat());
        assertEquals(config.getMaxTokens(), body.getMaxTokens());
        assertFalse(body.getMessages().get(0).getContent().contains("JSON Schema"));
    }

    @Test
    void maxTokensComeFromTheRequestAndZeroOmitsTheField() {
        PluginConfig config = config();
        GenerationOverrides requested = StructuredOutputSupport.call(GenerationOverrides.none().withMaxTokens(200), QUEST);
        StructuredOutputSupport.prepare(requested, "openai", "gpt-4o-mini", "auto");
        assertEquals(200, OpenAiProvider.buildBody(config, "Invent a quest", requested).getMaxTokens());

        GenerationOverrides omitted = StructuredOutputSupport.call(GenerationOverrides.none().withMaxTokens(0), QUEST);
        StructuredOutputSupport.prepare(omitted, "openai", "gpt-4o-mini", "auto");
        assertNull(OpenAiProvider.buildBody(config, "Invent a quest", omitted).getMaxTokens());
    }

    @Test
    void autoStepsDownAndReloadTriesJsonSchemaAgain() {
        Logger logger = Logger.getLogger("structured-output-test");
        GenerationOverrides first = prepared("auto");
        assertTrue(StructuredOutputSupport.downgrade(
                first, "openrouter", "some-model",
                new AiRequestException(AiErrorKind.OTHER, 400, "response_format is not supported", null),
                logger));
        assertEquals(StructuredMode.JSON_OBJECT, StructuredOutputSupport.active(first));
        assertEquals(StructuredMode.JSON_OBJECT, StructuredOutputSupport.remembered("openrouter", "some-model"));

        GenerationOverrides second = prepared("auto");
        assertEquals(StructuredMode.JSON_OBJECT, StructuredOutputSupport.active(second));
        assertTrue(StructuredOutputSupport.downgrade(
                second, "openrouter", "some-model",
                new AiRequestException(AiErrorKind.OTHER, 422, "json_object is not supported", null),
                logger));
        assertEquals(StructuredMode.PROMPT_ONLY, StructuredOutputSupport.active(second));

        GenerationOverrides third = prepared("auto");
        assertEquals(StructuredMode.PROMPT_ONLY, StructuredOutputSupport.active(third));
        assertFalse(StructuredOutputSupport.downgrade(
                third, "openrouter", "some-model",
                new AiRequestException(AiErrorKind.OTHER, 400, "response_format is not supported", null),
                logger));

        StructuredOutputSupport.clear();
        assertEquals(StructuredMode.JSON_SCHEMA, StructuredOutputSupport.active(prepared("auto")));
    }

    @Test
    void aFixedModeAndAServerErrorDoNotStepDown() {
        Logger logger = Logger.getLogger("structured-output-fixed");
        GenerationOverrides fixed = prepared("json-schema");
        assertFalse(StructuredOutputSupport.downgrade(
                fixed, "openai", "gpt-4o-mini",
                new AiRequestException(AiErrorKind.OTHER, 400, "response_format is not supported", null),
                logger));
        assertEquals(StructuredMode.JSON_SCHEMA, StructuredOutputSupport.active(fixed));
        assertNull(StructuredOutputSupport.remembered("openai", "gpt-4o-mini"));

        GenerationOverrides auto = prepared("auto");
        assertFalse(StructuredOutputSupport.downgrade(
                auto, "openai", "gpt-4o-mini",
                new AiRequestException(AiErrorKind.OTHER, 500, "server error", null),
                logger));
        assertEquals(StructuredMode.JSON_SCHEMA, StructuredOutputSupport.active(auto));
    }

    @Test
    void anUnknownProviderModeWarnsAndUsesAuto() {
        YamlConfiguration yaml = yaml();
        yaml.set("providers.openai.structured-output", "nope");
        PluginConfig config = new PluginConfig(yaml);
        assertNull(config.provider("openai").structuredOutput());
        assertEquals(
                List.of("providers.openai.structured-output 'nope' is unknown. Using auto."),
                config.structuredOutputWarnings());
    }

    private static GenerationOverrides prepared(String mode) {
        GenerationOverrides overrides = StructuredOutputSupport.call(GenerationOverrides.none().withFormat("chat"), QUEST);
        StructuredOutputSupport.prepare(overrides, "openrouter", "some-model", mode);
        return overrides;
    }

    private static JsonSchema openSchema() {
        return JsonSchema.parse("""
                {"type":"object","properties":{"title":{"type":"string"}}}
                """);
    }

    private static PluginConfig config() {
        return new PluginConfig(yaml());
    }

    private static YamlConfiguration yaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.max-tokens", 256);
        yaml.set("api.key", "");
        yaml.set("fallback", "...");
        yaml.createSection("providers.openai");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", "sk-one-1111");
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
        return yaml;
    }
}
