package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationTest {

    private static final String V06 = """
            # keep-me
            locale: de
            api:
              provider: groq
              model: llama-3.3-70b-versatile
              base-url: "https://example.test/v1/"
              key: "sk-user-secret-1234"
              system-prompt: "Be brief"
              temperature: 0.2
              max-tokens: 64
            cache:
              ttl: 120
            fallback: "hold"
            pool:
              entries:
                - prompt: survival_tips
                  size: 3
                  min-threshold: 1
            """;

    @Test
    void bundledConfigFilesParse() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(Path.of("src/main/resources/config.yml").toFile());
        assertEquals(ConfigVersions.CONFIG, config.getInt("config-version"));
        assertFalse(config.getBoolean("sanitize.allow-markup"));
        assertTrue(Files.readString(Path.of("src/main/resources/config.yml")).contains(ConfigMerger.SANITIZE_COMMENT));
        assertEquals(256, config.getInt("api.max-tokens"));
        assertEquals("openai-compatible", config.getString("providers.openai.type"));
        assertEquals("https://api.openai.com/v1", config.getString("providers.openai.url"));
        assertEquals("", config.getString("providers.openai.api-key"));
        assertEquals("gemini", config.getString("providers.gemini.type"));
        assertEquals("simple", config.getString("formats.default"));
        assertEquals(40, config.getInt("formats.hologram.max-chars-per-line"));
        assertEquals(6, config.getInt("formats.gui.max-lines"));
        var prompts = io.github.neareststep.nexusai.prompt.PromptCatalog.parse(
                Files.readString(Path.of("src/main/resources/prompts.yml")));
        assertTrue(prompts.valid(), prompts.error());
        assertTrue(prompts.warnings().isEmpty(), prompts.warnings().toString());
    }

    @Test
    void migrates060ConfigWithoutDroppingValues() {
        ConfigMigrator.Outcome outcome = ConfigMigrator.migrateConfig(V06);
        assertTrue(outcome.changed());
        assertEquals(0, outcome.fromVersion());
        assertEquals(ConfigVersions.CONFIG, outcome.toVersion());
        assertTrue(outcome.yaml().contains("# keep-me"));
        assertTrue(outcome.changes().stream().noneMatch(change -> change.contains("sk-user-secret-1234")));

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(outcome.yaml());
        } catch (Exception e) {
            throw new AssertionError(outcome.yaml(), e);
        }
        assertEquals(ConfigVersions.CONFIG, yaml.getInt("config-version"));
        assertEquals("de", yaml.getString("locale"));
        assertEquals("Be brief", yaml.getString("api.system-prompt"));
        assertEquals(0.2d, yaml.getDouble("api.temperature"), 0.0001);
        assertEquals(64, yaml.getInt("api.max-tokens"));
        assertEquals("sk-user-secret-1234", yaml.getString("api.key"));
        assertEquals("hold", yaml.getString("fallback"));
        assertEquals(120, yaml.getInt("cache.ttl"));
        assertEquals("survival_tips", yaml.getMapList("pool.entries").getFirst().get("prompt"));
        assertEquals("openai-compatible", yaml.getString("providers.groq.type"));
        assertEquals("https://example.test/v1", yaml.getString("providers.groq.url"));
        assertEquals("sk-user-secret-1234", yaml.getString("providers.groq.api-key"));
        assertEquals("https://api.openai.com/v1", yaml.getString("providers.openai.url"));
        assertEquals("", yaml.getString("providers.openai.api-key"));
        assertEquals("gemini", yaml.getString("providers.gemini.type"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", yaml.getString("providers.gemini.url"));
        assertEquals("groq", String.valueOf(yaml.getMapList("model-queue").getFirst().get("provider")));
        assertEquals("llama-3.3-70b-versatile", String.valueOf(yaml.getMapList("model-queue").getFirst().get("model")));
        assertEquals("simple", yaml.getString("formats.default"));
        assertTrue(yaml.contains("formats.hologram.max-chars-per-line"));

        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("NEXUSAI_API_KEY") == null || System.getenv("NEXUSAI_API_KEY").isBlank());
        PluginConfig loaded = new PluginConfig(yaml);
        assertEquals("groq", loaded.getProvider());
        assertEquals("https://example.test/v1", loaded.getBaseUrl());
        assertEquals("sk-user-secret-1234", loaded.getApiKey());
        assertEquals("llama-3.3-70b-versatile", loaded.modelQueue().getFirst().model());
        assertEquals("****1234", loaded.maskedApiKeys());
    }

    @Test
    void missingMaxTokensIsAppendedWithTheBackupRule() throws Exception {
        String defaults = Files.readString(Path.of("src/main/resources/config.yml"));
        String without = defaults.replace("  max-tokens: 256\n", "");
        assertFalse(without.contains("max-tokens: 256"));
        Path dir = Files.createTempDirectory("nexusai-max-tokens");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, without, StandardCharsets.UTF_8);
        Path existingBak = dir.resolve("config.yml.bak");
        Files.writeString(existingBak, "OLD\n", StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("max-tokens-merge");

        ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(outcome.valid());
        assertTrue(outcome.addedKeys().contains("api.max-tokens"));
        assertEquals("OLD\n", Files.readString(existingBak, StandardCharsets.UTF_8));
        assertTrue(outcome.backup().getFileName().toString().startsWith("config.yml.bak."));
        assertEquals(without, Files.readString(outcome.backup(), StandardCharsets.UTF_8));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(256, yaml.getInt("api.max-tokens"));
        assertEquals(80, yaml.getInt("moderation.max-tokens"));
        assertEquals(PluginConfig.DEFAULT_MAX_TOKENS, new PluginConfig(yaml).getMaxTokens());

        String keptZero = defaults.replace("  max-tokens: 256\n", "  max-tokens: 0\n");
        Path kept = dir.resolve("kept.yml");
        Files.writeString(kept, keptZero, StandardCharsets.UTF_8);
        ConfigStartup.Outcome unchanged = ConfigStartup.prepareConfig(kept, defaults, logger);
        assertTrue(unchanged.addedKeys().isEmpty());
        YamlConfiguration zero = new YamlConfiguration();
        zero.loadFromString(Files.readString(kept, StandardCharsets.UTF_8));
        assertEquals(0, zero.getInt("api.max-tokens"));
        assertEquals(null, new PluginConfig(zero).getMaxTokens());

        String legacy = V06.replace("  max-tokens: 64\n", "");
        assertFalse(legacy.contains("max-tokens:"));
        Path legacyFile = dir.resolve("legacy.yml");
        Files.writeString(legacyFile, legacy, StandardCharsets.UTF_8);
        ConfigStartup.Outcome migrated = ConfigStartup.prepareConfig(legacyFile, defaults, logger);
        assertTrue(migrated.valid());
        assertTrue(migrated.addedKeys().contains("api.max-tokens"));
        List<Path> backups = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("legacy.yml.bak"))
                .toList();
        assertEquals(1, backups.size());
        assertEquals(legacy, Files.readString(backups.getFirst(), StandardCharsets.UTF_8));
        YamlConfiguration gained = new YamlConfiguration();
        gained.loadFromString(Files.readString(legacyFile, StandardCharsets.UTF_8));
        assertEquals(256, gained.getInt("api.max-tokens"));
        assertEquals(0.2d, gained.getDouble("api.temperature"), 0.0001);
        assertEquals("sk-user-secret-1234", gained.getString("api.key"));
    }

    @Test
    void migrationAndMissingKeysShareOneBackupPerStart() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-one-backup");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, V06, StandardCharsets.UTF_8);
        String defaults = Files.readString(Path.of("src/main/resources/config.yml"));
        Logger logger = Logger.getLogger("one-backup");
        ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(outcome.valid());
        assertFalse(outcome.addedKeys().isEmpty());
        assertEquals(null, outcome.backup());
        List<Path> backups = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("config.yml.bak"))
                .toList();
        assertEquals(1, backups.size());
        assertEquals(V06, Files.readString(backups.getFirst(), StandardCharsets.UTF_8));
        assertTrue(Files.readString(file).contains("config-version:"));

        ConfigStartup.Outcome second = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(second.valid());
        assertTrue(second.addedKeys().isEmpty());
        long stillOne = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("config.yml.bak"))
                .count();
        assertEquals(1, stillOne);
    }

    @Test
    void secondMigrationIsANoOpAndBackupIsTimestampedWhenOneExists() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-migrate");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, V06, StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("migrate-test");
        List<String> first = ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger);
        assertFalse(first.isEmpty());
        assertTrue(Files.exists(dir.resolve("config.yml.bak")));
        String migrated = Files.readString(file);
        assertTrue(migrated.contains("sk-user-secret-1234"));
        assertTrue(migrated.contains("# keep-me"));

        List<String> second = ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger);
        assertTrue(second.isEmpty());

        String downgraded = migrated.replace("config-version: " + ConfigVersions.CONFIG, "config-version: 0");
        Files.writeString(file, downgraded, StandardCharsets.UTF_8);
        List<String> third = ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger);
        assertFalse(third.isEmpty());
        assertTrue(Files.list(dir).anyMatch(path -> path.getFileName().toString().startsWith("config.yml.bak.")));
        assertTrue(Files.readString(file).contains("sk-user-secret-1234"));
    }

    @Test
    void promptsMigrationKeepsIds() {
        String original = """
                # prompts
                survival_tips:
                  prompt: "Stay fed"
                welcome:
                  prompt: "Hello {biome}"
                  vars:
                    biome: "%player_biome%"
                """;
        ConfigMigrator.Outcome outcome = ConfigMigrator.migratePrompts(original);
        assertTrue(outcome.changed());
        assertTrue(outcome.yaml().contains("# prompts"));
        assertTrue(outcome.yaml().contains("survival_tips:"));
        assertTrue(outcome.yaml().contains("biome: \"%player_biome%\"") || outcome.yaml().contains("biome: \"%player_biome%\""));
        var parsed = io.github.neareststep.nexusai.prompt.PromptCatalog.parse(outcome.yaml());
        assertTrue(parsed.valid(), parsed.error());
        assertEquals(List.of("survival_tips", "welcome"), parsed.catalog().ids());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
    }

    @Test
    void appendingMissingKeysBacksUpConfigBeforeWriting() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-merge");
        Path file = dir.resolve("config.yml");
        String original = "locale: de\n# keep\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        ConfigMerger.Result result = ConfigMerger.mergeMissing(original, "locale: en\nfallback: \"...\"\n");
        assertFalse(result.addedKeys().isEmpty());
        Path backup = FileBackup.replace(file, result.yaml());
        assertEquals(dir.resolve("config.yml.bak"), backup);
        assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("fallback:"));
        assertTrue(written.contains("# keep"));
        assertTrue(written.contains("locale: de"));

        Path second = FileBackup.replace(file, written + "extra: 1\n");
        assertTrue(second.getFileName().toString().startsWith("config.yml.bak."));
        assertEquals(original, Files.readString(dir.resolve("config.yml.bak"), StandardCharsets.UTF_8));
    }

    @Test
    void migrationRewritesBackUpPromptsPoolAndUsage() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-migrate-all");
        String prompts = "welcome:\n  prompt: \"Hi\"\n";
        String pool = "pools:\n- prompt: tip\n  answers:\n  - hello\n";
        String usage = "today: 1\n";
        Files.writeString(dir.resolve("prompts.yml"), prompts, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("pool.yml"), pool, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("usage.yml"), usage, StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("migrate-all");
        assertFalse(ConfigMigrator.migrateFile(dir.resolve("prompts.yml"), ConfigMigrator::migratePrompts, logger).isEmpty());
        assertFalse(ConfigMigrator.migrateFile(dir.resolve("pool.yml"), ConfigMigrator::migratePool, logger).isEmpty());
        assertFalse(ConfigMigrator.migrateFile(dir.resolve("usage.yml"), ConfigMigrator::migrateUsage, logger).isEmpty());
        assertEquals(prompts, Files.readString(dir.resolve("prompts.yml.bak"), StandardCharsets.UTF_8));
        assertEquals(pool, Files.readString(dir.resolve("pool.yml.bak"), StandardCharsets.UTF_8));
        assertEquals(usage, Files.readString(dir.resolve("usage.yml.bak"), StandardCharsets.UTF_8));
        assertTrue(Files.readString(dir.resolve("prompts.yml"), StandardCharsets.UTF_8).contains("config-version:"));
        assertTrue(Files.readString(dir.resolve("pool.yml"), StandardCharsets.UTF_8).contains("config-version:"));
        assertTrue(Files.readString(dir.resolve("usage.yml"), StandardCharsets.UTF_8).contains("config-version:"));
    }

    @Test
    void poolMigrationQuotesAnswersAndStillReadsWrappedFiles() throws Exception {
        String old = """
                pools:
                - prompt: "Say: \\"hi\\""
                  answers:
                  - plain answer
                  - 'wrapped line
                    continues here'
                """;
        ConfigMigrator.Outcome outcome = ConfigMigrator.migratePool(old);
        assertTrue(outcome.changed());
        assertTrue(outcome.yaml().contains("config-version: 1"));
        assertTrue(outcome.yaml().contains("\"plain answer\""));
        assertFalse(outcome.yaml().contains("\n    continues"));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(outcome.yaml());
        List<java.util.Map<?, ?>> rows = yaml.getMapList("pools");
        assertEquals("Say: \"hi\"", rows.getFirst().get("prompt"));
        @SuppressWarnings("unchecked")
        List<String> answers = (List<String>) rows.getFirst().get("answers");
        assertEquals("plain answer", answers.get(0));
        assertTrue(answers.get(1).contains("continues here"));
    }

    @Test
    void version1ReplacesOnlyTheOldZeroMaxTokens() throws Exception {
        String zero = """
                # keep-me
                config-version: 1
                locale: de
                api:
                  provider: groq
                  # cap
                  max-tokens: 0 # old default
                  temperature: 0.2
                moderation:
                  max-tokens: 0
                """;
        ConfigMigrator.Outcome replaced = ConfigMigrator.migrateConfig(zero);
        assertTrue(replaced.changed());
        assertEquals(1, replaced.fromVersion());
        assertEquals(ConfigVersions.CONFIG, replaced.toVersion());
        assertTrue(replaced.yaml().contains("# keep-me"));
        assertTrue(replaced.yaml().contains("# cap"));
        assertTrue(replaced.yaml().contains("temperature: 0.2"));
        assertTrue(replaced.yaml().contains("max-tokens: 256 # old default"));
        assertTrue(replaced.changes().getFirst().contains("Set api.max-tokens back to 0"));
        YamlConfiguration zeroYaml = load(replaced.yaml());
        assertEquals(256, zeroYaml.getInt("api.max-tokens"));
        assertEquals(0, zeroYaml.getInt("moderation.max-tokens"));
        assertEquals(0.2d, zeroYaml.getDouble("api.temperature"), 0.0001);
        ConfigMigrator.Outcome again = ConfigMigrator.migrateConfig(replaced.yaml());
        assertFalse(again.changed());

        String keptPositive = zero.replace("max-tokens: 0 # old default", "max-tokens: 512 # old default");
        ConfigMigrator.Outcome positive = ConfigMigrator.migrateConfig(keptPositive);
        assertEquals(512, load(positive.yaml()).getInt("api.max-tokens"));
        assertTrue(positive.yaml().contains("max-tokens: 512 # old default"));
        assertEquals(ConfigVersions.CONFIG, load(positive.yaml()).getInt("config-version"));

        String keptNegative = zero.replace("max-tokens: 0 # old default", "max-tokens: -1");
        ConfigMigrator.Outcome negative = ConfigMigrator.migrateConfig(keptNegative);
        assertEquals(-1, load(negative.yaml()).getInt("api.max-tokens"));
        assertTrue(negative.yaml().contains("max-tokens: -1"));
        assertFalse(negative.changes().getFirst().contains("replaced api.max-tokens"));

        String quoted = zero.replace("max-tokens: 0 # old default", "max-tokens: \"0\"");
        assertEquals(256, load(ConfigMigrator.migrateConfig(quoted).yaml()).getInt("api.max-tokens"));

        String crlf = "config-version: 1\r\napi:\r\n  max-tokens: 0\r\nlocale: de\r\n";
        ConfigMigrator.Outcome crlfOutcome = ConfigMigrator.migrateConfig(crlf);
        assertTrue(crlfOutcome.yaml().contains("\r\n"));
        assertTrue(crlfOutcome.yaml().contains("max-tokens: 256"));
        assertEquals(256, load(crlfOutcome.yaml()).getInt("api.max-tokens"));

        String legacyZero = V06.replace("max-tokens: 64", "max-tokens: 0");
        ConfigMigrator.Outcome fromZero = ConfigMigrator.migrateConfig(legacyZero);
        assertEquals(0, fromZero.fromVersion());
        assertEquals(ConfigVersions.CONFIG, fromZero.toVersion());
        assertEquals(256, load(fromZero.yaml()).getInt("api.max-tokens"));
        assertTrue(fromZero.yaml().contains("# keep-me"));
        assertTrue(fromZero.yaml().contains("sk-user-secret-1234"));

        String versionTwo = replaced.yaml().replace("max-tokens: 256 # old default", "max-tokens: 0 # set back");
        ConfigMigrator.Outcome stuck = ConfigMigrator.migrateConfig(versionTwo);
        assertFalse(stuck.changed());
        assertEquals(0, load(versionTwo).getInt("api.max-tokens"));
        assertTrue(versionTwo.contains("max-tokens: 0 # set back"));
    }

    @Test
    void version1ZeroMigrationWritesOneBackupAndOneInfoLine() throws Exception {
        String original = """
                # keep-me
                config-version: 1
                locale: de
                api:
                  max-tokens: 0
                  temperature: 0.2
                """;
        Path dir = Files.createTempDirectory("nexusai-v2");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("migrate-v2-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        List<LogRecord> infos = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.INFO) {
                    infos.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            List<String> notes = ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger);
            assertEquals(1, notes.size());
            assertTrue(notes.getFirst().contains("Set api.max-tokens back to 0"));
            assertEquals(1, infos.size());
            assertTrue(infos.getFirst().getMessage().contains("migrated from version 1 to 2"));
            assertTrue(infos.getFirst().getMessage().contains("Set api.max-tokens back to 0"));
            assertFalse(infos.getFirst().getMessage().contains("\n"));
            List<Path> backups = Files.list(dir)
                    .filter(path -> path.getFileName().toString().startsWith("config.yml.bak"))
                    .toList();
            assertEquals(1, backups.size());
            assertEquals("config.yml.bak", backups.getFirst().getFileName().toString());
            assertEquals(original, Files.readString(backups.getFirst(), StandardCharsets.UTF_8));
            String migrated = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(migrated.contains("# keep-me"));
            assertTrue(migrated.contains("temperature: 0.2"));
            assertEquals(256, load(migrated).getInt("api.max-tokens"));
            assertEquals(ConfigVersions.CONFIG, load(migrated).getInt("config-version"));

            assertTrue(ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger).isEmpty());
            long stillOne = Files.list(dir)
                    .filter(path -> path.getFileName().toString().startsWith("config.yml.bak"))
                    .count();
            assertEquals(1, stillOne);
            assertEquals(1, infos.size());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void missingMaxTokensOnVersion1IsAppendedOnce() throws Exception {
        String defaults = Files.readString(Path.of("src/main/resources/config.yml"));
        String original = defaults
                .replace("config-version: " + ConfigVersions.CONFIG, "config-version: 1")
                .replace("  max-tokens: 256\n", "");
        assertFalse(original.contains("max-tokens: 256"));
        Path dir = Files.createTempDirectory("nexusai-v1-missing");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("migrate-missing-" + UUID.randomUUID());

        ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(outcome.valid());
        assertTrue(outcome.addedKeys().contains("api.max-tokens"));
        assertEquals(null, outcome.backup());
        List<Path> backups = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("config.yml.bak"))
                .toList();
        assertEquals(1, backups.size());
        assertEquals(original, Files.readString(backups.getFirst(), StandardCharsets.UTF_8));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(256, yaml.getInt("api.max-tokens"));
        assertEquals(ConfigVersions.CONFIG, yaml.getInt("config-version"));

        ConfigStartup.Outcome second = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(second.addedKeys().isEmpty());
        long stillOne = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("config.yml.bak"))
                .count();
        assertEquals(1, stillOne);

        String versionTwo = Files.readString(file, StandardCharsets.UTF_8).replace("  max-tokens: 256\n", "  max-tokens: 0\n");
        Path kept = dir.resolve("kept.yml");
        Files.writeString(kept, versionTwo, StandardCharsets.UTF_8);
        ConfigStartup.Outcome untouched = ConfigStartup.prepareConfig(kept, defaults, logger);
        assertTrue(untouched.addedKeys().isEmpty());
        assertEquals(0, load(Files.readString(kept, StandardCharsets.UTF_8)).getInt("api.max-tokens"));
        assertEquals(null, new PluginConfig(load(Files.readString(kept))).getMaxTokens());
        assertFalse(Files.exists(dir.resolve("kept.yml.bak")));
    }

    @Test
    void appendsSanitizeSectionWithoutBumpingConfigVersion() throws Exception {
        String defaults = Files.readString(Path.of("src/main/resources/config.yml"));
        int marker = defaults.indexOf("\n" + ConfigMerger.SANITIZE_COMMENT);
        assertTrue(marker > 0);
        String original = defaults.substring(0, marker).stripTrailing() + "\n";
        assertFalse(original.contains("allow-markup"));
        assertTrue(original.contains("config-version: " + ConfigVersions.CONFIG));

        Path dir = Files.createTempDirectory("nexusai-sanitize");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("migrate-sanitize-" + UUID.randomUUID());

        ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(outcome.valid());
        assertTrue(outcome.addedKeys().contains("sanitize.allow-markup"));
        assertTrue(outcome.backup() != null);
        assertEquals(original, Files.readString(outcome.backup(), StandardCharsets.UTF_8));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(1, written.split(java.util.regex.Pattern.quote(ConfigMerger.SANITIZE_COMMENT), -1).length - 1);
        assertTrue(written.contains("allow-markup: false"));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(written);
        assertEquals(ConfigVersions.CONFIG, yaml.getInt("config-version"));
        assertFalse(yaml.getBoolean("sanitize.allow-markup"));

        ConfigStartup.Outcome second = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(second.addedKeys().isEmpty());
        assertEquals(written, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void migrationCopiesTheRawApiKeyPlaceholder() {
        Function<String, String> previous = PluginConfig.environment;
        PluginConfig.environment = name -> "sk-canary-resolved";
        try {
            String yaml = """
                    api:
                      provider: groq
                      model: llama-3.3-70b-versatile
                      key: "${GROQ}"
                    """;
            ConfigMigrator.Outcome outcome = ConfigMigrator.migrateConfig(yaml);
            assertTrue(outcome.yaml().contains("${GROQ}"), outcome.yaml());
            assertFalse(outcome.yaml().contains("sk-canary-resolved"), outcome.yaml());
            assertEquals("${GROQ}", load(outcome.yaml()).getString("providers.groq.api-key"));
            String rendered = ConfigMigrator.renderProviders("groq", "https://example.test/v1", "${GROQ}");
            assertTrue(rendered.contains("${GROQ}"), rendered);
            assertFalse(rendered.contains("sk-canary-resolved"), rendered);
        } finally {
            PluginConfig.environment = previous;
        }
    }

    @Test
    void backupKeepsOwnerOnlyPosixPermissions() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-bak-mode");
        Path source = dir.resolve("config.yml");
        Files.writeString(source, "api:\n  key: \"literal\"\n", StandardCharsets.UTF_8);
        PosixFileAttributeView view = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null, "POSIX permissions are not available");
        Files.setPosixFilePermissions(source, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Path bak = FileBackup.backup(source);
        Set<PosixFilePermission> copied = Files.getPosixFilePermissions(bak);
        assertTrue(copied.contains(PosixFilePermission.OWNER_READ));
        assertFalse(copied.contains(PosixFilePermission.GROUP_READ));
        assertFalse(copied.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    void replaceKeepsOwnerOnlyPosixPermissions() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-replace-mode");
        Path source = dir.resolve("config.yml");
        Files.writeString(source, "api:\n  key: \"literal\"\n", StandardCharsets.UTF_8);
        PosixFileAttributeView view = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null, "POSIX permissions are not available");
        Set<PosixFilePermission> mode = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(source, mode);
        FileBackup.replace(source, "api:\n  key: \"\"\n");
        assertEquals(mode, Files.getPosixFilePermissions(source));
        assertTrue(Files.readString(source).contains("key: \"\""));
    }

    @Test
    void httpLimitsAreAppendedOnceAndCommentsStay() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-http-merge");
        Path file = dir.resolve("config.yml");
        String original = """
                # keep this comment
                config-version: 2
                api:
                  provider: openai
                  model: gpt-4o-mini
                  max-tokens: 256
                """;
        Files.writeString(file, original, StandardCharsets.UTF_8);
        String defaults = Files.readString(Path.of("src/main/resources/config.yml"));
        Logger logger = Logger.getLogger("http-merge");
        ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(outcome.addedKeys().contains("http.max-in-flight"));
        assertTrue(outcome.addedKeys().contains("http.queue-size"));
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("# keep this comment"), written);
        assertEquals(1, written.split("max-in-flight:", -1).length - 1, written);
        assertEquals(1, written.split("queue-size:", -1).length - 1, written);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(written);
        assertEquals(64, yaml.getInt("http.max-in-flight"));
        assertEquals(64, yaml.getInt("http.queue-size"));

        ConfigStartup.Outcome second = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(second.addedKeys().isEmpty());
        assertEquals(written, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void pluginApiKeysAreAppendedOnce() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-plugin-api-merge");
        Path file = dir.resolve("config.yml");
        String original = """
                # keep this comment
                config-version: 2
                api:
                  provider: openai
                  model: gpt-4o-mini
                """;
        Files.writeString(file, original, StandardCharsets.UTF_8);
        String defaults = Files.readString(Path.of("src/main/resources/config.yml"));
        Logger logger = Logger.getLogger("plugin-api-merge");
        ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(outcome.addedKeys().contains("plugin-api.enabled"), outcome.addedKeys().toString());
        assertTrue(outcome.addedKeys().contains("plugin-api.max-template-chars"));
        assertTrue(outcome.addedKeys().contains("plugin-api.max-var-chars"));
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("# keep this comment"), written);
        assertEquals(1, written.split("max-template-chars:", -1).length - 1, written);
        assertEquals(1, written.split("max-var-chars:", -1).length - 1, written);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(written);
        assertEquals(2, yaml.getInt("config-version"));
        assertTrue(yaml.getBoolean("plugin-api.enabled"));
        assertEquals(8000, yaml.getInt("plugin-api.max-template-chars"));
        assertEquals(1000, yaml.getInt("plugin-api.max-var-chars"));

        ConfigStartup.Outcome again = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(again.addedKeys().isEmpty());
        assertEquals(written, Files.readString(file, StandardCharsets.UTF_8));
    }

    private static YamlConfiguration load(String yaml) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (Exception e) {
            throw new AssertionError(yaml, e);
        }
        return parsed;
    }
}
