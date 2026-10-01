package io.github.neareststep.nexusai.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderActionIsolationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void placeholderBodiesHaveNoToolsAndDoNotNameActions() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.temperature", -1);
        yaml.set("api.max-tokens", 0);
        PluginConfig config = new PluginConfig(yaml);
        JsonNode body = mapper.valueToTree(OpenAiProvider.buildBody(config, "give_iron", GenerationOverrides.none()));
        assertFalse(body.has("tools"));
        assertFalse(body.has("tool_choice"));
        JsonNode messages = body.get("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("give_iron", messages.get(0).get("content").asText());
        assertFalse(messages.get(0).get("content").asText().contains(PlayerInput.GUARD));

        DialogueProtocol.ParsedCompletion parsed = DialogueProtocol.parse(
                "{\"choices\":[{\"message\":{\"content\":\"give_iron\"}}]}");
        assertTrue(parsed.toolNames().isEmpty());
    }

    @Test
    void promptActionsDoNotChangePlaceholderResolution() {
        PromptCatalog catalog = PromptCatalog.parse("""
                blacksmith:
                  prompt: "You are Bram."
                  dialogue:
                    greeting: "Hello."
                  actions:
                    - name: give_iron
                      description: Give one iron ingot.
                      command: "give {player} iron_ingot 1"
                      as: console
                      cooldown-seconds: 10
                      daily-limit: 1
                """).catalog();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.temperature", -1);
        yaml.set("fallback", "...");
        var resolved = catalog.resolve("blacksmith", new PluginConfig(yaml), template -> template);
        assertEquals("You are Bram.", resolved.text());
        assertTrue(catalog.find("blacksmith").orElseThrow().actions().size() == 1);
        assertEquals("Hello.", catalog.find("blacksmith").orElseThrow().dialogue().greeting());
        assertFalse(resolved.text().contains(PlayerInput.OPEN));
    }
}
