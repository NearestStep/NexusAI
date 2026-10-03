package io.github.neareststep.nexusai.config;

import io.github.neareststep.nexusai.ai.KeyRing;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyFileTest {

    @TempDir
    Path dir;

    @Test
    void aKeyFileIsRoundRobinAndBeatsTheLiteralAndTheEnvOverride() throws Exception {
        Path file = dir.resolve("secrets/groq.key");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                # comment
                sk-file-aaaa

                sk-file-bbbb
                """, StandardCharsets.UTF_8);
        Function<String, String> previousEnv = PluginConfig.environment;
        Path previousBase = PluginConfig.secretsBase;
        PluginConfig.environment = name -> "NEXUSAI_API_KEY".equals(name) ? "sk-env-override-9999" : null;
        PluginConfig.secretsBase = dir;
        try {
            YamlConfiguration yaml = base();
            yaml.set("api.provider", "groq");
            yaml.set("providers.groq.api-key", "sk-literal-should-not-win");
            yaml.set("providers.groq.api-key-file", "secrets/groq.key");
            PluginConfig config = new PluginConfig(yaml);
            ProviderSettings groq = config.provider("groq");
            assertEquals(List.of("sk-file-aaaa", "sk-file-bbbb"), groq.apiKeys());
            assertEquals(KeySource.FILE, groq.keySource());
            assertEquals("****aaaa, ****bbbb (file)", config.maskedApiKeys());
            assertFalse(config.maskedApiKeys().contains("sk-file"));
            assertFalse(config.maskedApiKeys().contains("sk-literal"));
            assertFalse(config.maskedApiKeys().contains("sk-env"));
            KeyRing ring = new KeyRing(groq.apiKeys());
            assertEquals("sk-file-aaaa", ring.acquire(0L));
            assertEquals("sk-file-bbbb", ring.acquire(0L));
            String warnings = String.join("\n", config.keyFileWarnings());
            assertTrue(warnings.contains("providers.groq.api-key is ignored"), warnings);
            assertFalse(warnings.contains("sk-literal-should-not-win"), warnings);
            assertFalse(warnings.contains("sk-file-aaaa"), warnings);
        } finally {
            PluginConfig.environment = previousEnv;
            PluginConfig.secretsBase = previousBase;
        }
    }

    @Test
    void anAbsolutePathIsReadAndAnEmptyFileFallsBackToTheEnvKey() throws Exception {
        Path absolute = dir.resolve("absolute.key");
        Files.writeString(absolute, "sk-abs-zzzz\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("empty.key"), "# nothing\n\n", StandardCharsets.UTF_8);
        Function<String, String> previousEnv = PluginConfig.environment;
        Path previousBase = PluginConfig.secretsBase;
        PluginConfig.environment = name -> "NEXUSAI_API_KEY".equals(name) ? "sk-fallback-4242" : null;
        PluginConfig.secretsBase = dir;
        try {
            YamlConfiguration absoluteYaml = base();
            absoluteYaml.set("providers.openai.api-key-file", absolute.toString());
            PluginConfig fromAbsolute = new PluginConfig(absoluteYaml);
            assertEquals(List.of("sk-abs-zzzz"), fromAbsolute.provider("openai").apiKeys());
            assertEquals(KeySource.FILE, fromAbsolute.provider("openai").keySource());

            YamlConfiguration empty = base();
            empty.set("providers.openai.api-key", "sk-literal-ignored");
            empty.set("providers.openai.api-key-file", "empty.key");
            PluginConfig fromEmpty = new PluginConfig(empty);
            assertEquals(List.of("sk-fallback-4242"), fromEmpty.provider("openai").apiKeys());
            assertEquals(KeySource.ENV, fromEmpty.provider("openai").keySource());
            assertEquals("****4242 (env)", fromEmpty.maskedApiKeys());
            assertFalse(String.join("\n", fromEmpty.keyFileWarnings()).contains("sk-literal-ignored"));
        } finally {
            PluginConfig.environment = previousEnv;
            PluginConfig.secretsBase = previousBase;
        }
    }

    @Test
    void envPrefixAndBareNameResolveTheSameKey() {
        Function<String, String> previousEnv = PluginConfig.environment;
        PluginConfig.environment = name -> "GROQ_API_KEY".equals(name) ? "sk-groq-same" : null;
        try {
            YamlConfiguration prefixed = base();
            prefixed.set("providers.openai.api-key", "${ENV:GROQ_API_KEY}");
            YamlConfiguration bare = base();
            bare.set("providers.openai.api-key", "${GROQ_API_KEY}");
            PluginConfig fromPrefix = new PluginConfig(prefixed);
            PluginConfig fromBare = new PluginConfig(bare);
            assertEquals(fromBare.provider("openai").apiKeys(), fromPrefix.provider("openai").apiKeys());
            assertEquals(List.of("sk-groq-same"), fromPrefix.provider("openai").apiKeys());
            assertEquals(KeySource.ENV, fromPrefix.provider("openai").keySource());
            assertEquals("****same (env)", fromPrefix.maskedApiKeys());

            PluginConfig.environment = name -> null;
            YamlConfiguration missing = base();
            missing.set("providers.openai.api-key", "${ENV:MISSING_GROQ}");
            PluginConfig unset = new PluginConfig(missing);
            assertTrue(unset.provider("openai").apiKeys().isEmpty());
            assertEquals(List.of("MISSING_GROQ"), unset.missingEnvVars());
        } finally {
            PluginConfig.environment = previousEnv;
        }
    }

    @Test
    void aLiteralKeyStillBehavesAsBefore() {
        Function<String, String> previousEnv = PluginConfig.environment;
        PluginConfig.environment = name -> null;
        try {
            YamlConfiguration yaml = base();
            yaml.set("providers.openai.api-key", "sk-literal-1111");
            PluginConfig config = new PluginConfig(yaml);
            assertEquals(List.of("sk-literal-1111"), config.provider("openai").apiKeys());
            assertEquals(KeySource.CONFIG, config.provider("openai").keySource());
            assertEquals("****1111", config.maskedApiKeys());
        } finally {
            PluginConfig.environment = previousEnv;
        }
    }

    @Test
    void aMissingOversizedOrMalformedKeyFileWarnsWithoutTheSecret() throws Exception {
        Path huge = dir.resolve("huge.key");
        Files.write(huge, new byte[KeyFiles.MAX_BYTES + 1]);
        Path odd = dir.resolve("odd.key");
        Files.writeString(odd, "sk-live:extra token\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(odd, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        Path previousBase = PluginConfig.secretsBase;
        Function<String, String> previousEnv = PluginConfig.environment;
        PluginConfig.secretsBase = dir;
        PluginConfig.environment = name -> null;
        try {
            YamlConfiguration missing = base();
            missing.set("providers.openai.api-key-file", "secrets/missing.key");
            PluginConfig missingConfig = new PluginConfig(missing);
            assertTrue(missingConfig.provider("openai").apiKeys().isEmpty());
            String missingWarning = String.join("\n", missingConfig.keyFileWarnings());
            assertTrue(missingWarning.contains("secrets/missing.key"), missingWarning);
            assertFalse(missingWarning.contains("Exception"), missingWarning);

            YamlConfiguration oversized = base();
            oversized.set("providers.openai.api-key-file", huge.toString());
            PluginConfig oversizedConfig = new PluginConfig(oversized);
            assertTrue(oversizedConfig.provider("openai").apiKeys().isEmpty());
            String sizeWarning = String.join("\n", oversizedConfig.keyFileWarnings());
            assertTrue(sizeWarning.contains("64KB"), sizeWarning);
            assertTrue(sizeWarning.contains(huge.toString()), sizeWarning);

            YamlConfiguration malformed = base();
            malformed.set("providers.openai.api-key-file", odd.toString());
            PluginConfig malformedConfig = new PluginConfig(malformed);
            assertTrue(malformedConfig.provider("openai").apiKeys().isEmpty());
            String oddWarning = String.join("\n", malformedConfig.keyFileWarnings());
            assertTrue(oddWarning.contains("line 1 of "), oddWarning);
            assertTrue(oddWarning.contains("does not look like a raw key"), oddWarning);
            assertFalse(oddWarning.contains("sk-live"), oddWarning);
            assertFalse(oddWarning.contains("extra token"), oddWarning);
            assertTrue(oddWarning.contains("chmod 600"), oddWarning);
        } finally {
            PluginConfig.environment = previousEnv;
            PluginConfig.secretsBase = previousBase;
        }
    }

    private static YamlConfiguration base() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "");
        yaml.set("api.key", "");
        yaml.set("cache.ttl", 300);
        yaml.set("cache.max-size", 100);
        yaml.set("limits.max-prompt-length", 128);
        yaml.set("fallback", "...");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", "");
        yaml.set("providers.groq.type", "openai-compatible");
        yaml.set("providers.groq.url", "https://api.groq.com/openai/v1");
        yaml.set("providers.groq.api-key", "");
        return yaml;
    }
}
