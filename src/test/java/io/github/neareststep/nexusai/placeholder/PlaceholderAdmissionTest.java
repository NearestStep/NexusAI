package io.github.neareststep.nexusai.placeholder;

import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import io.github.neareststep.nexusai.prompt.ResolvedPrompt;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderAdmissionTest {

    @Test
    void namedPromptUsesItsIdAndALiteralKeepsTheRenderedText() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "");
        yaml.set("fallback", "...");
        PluginConfig config = new PluginConfig(yaml);
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                gift:
                  prompt: "Say gift"
                """);
        assertTrue(parsed.valid(), parsed.error());
        NamedPrompt gift = parsed.catalog().find("gift").orElseThrow();
        ResolvedPrompt named = ResolvedPrompt.named(gift, "Say gift", config);
        assertEquals("gift", PlaceholderAdmission.key(named, "Say gift"));
        assertEquals("Say gift", PlaceholderAdmission.key(ResolvedPrompt.literal("Say gift", config), "Say gift"));
        assertEquals("", PlaceholderAdmission.key(null, null));
    }
}
