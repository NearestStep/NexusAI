package io.github.neareststep.nexusai.config;

import io.github.neareststep.nexusai.budget.MissingUsage;
import io.github.neareststep.nexusai.budget.QuotaSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Typed view over {@code config.yml} with environment-variable overrides for the API key.
 */
public final class PluginConfig {

    private static final String ENV_API_KEY = "NEXUSAI_API_KEY";

    /**
     * Sent when {@code api.max-tokens} is missing. {@code 0} and negative values omit the field.
     * Short placeholder and talk replies fit in this budget and stay under a low Groq output-token cap.
     */
    public static final int DEFAULT_MAX_TOKENS = 256;

    /** Test seam. Production reads the process environment. */
    static Function<String, String> environment = System::getenv;

    /**
     * Directory relative {@code api-key-file} paths are resolved from.
     * Production sets this to the plugin data folder before each load. Absolute paths ignore it.
     */
    public static volatile Path secretsBase;

    private final Set<String> missingEnvVars = new LinkedHashSet<>();
    private final List<String> keyFileWarnings = new ArrayList<>();

    private static final Map<String, String> PROVIDER_BASE_URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "groq", "https://api.groq.com/openai/v1",
            "cerebras", "https://api.cerebras.ai/v1",
            "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "deepseek", "https://api.deepseek.com",
            "ollama", "http://localhost:11434/v1",
            "openrouter", "https://openrouter.ai/api/v1"
    );

    private String provider;
    private String model;
    private String baseUrl;
    private String apiKey;
    private String systemPrompt;
    private Double temperature;
    private Integer maxTokens;
    private boolean stripMarkdown;
    private boolean allowMarkup;
    private int maxAnswerChars;
    private int maxAnswerLines;
    private String reasoningEffort;
    private Duration connectTimeout;
    private Duration readTimeout;
    private Duration cacheTtl;
    private long cacheMaxSize;
    private int requestsPerMinute;
    private int requestsPerDay;
    private int playerRequestsPerMinute;
    private int playerRequestsPerDay;
    private boolean requestsHeld;
    private int providerPauseSeconds;
    private int authPauseSeconds;
    private int errorBackoffInitialSeconds;
    private int errorBackoffMaxSeconds;
    private int errorLogCooldownSeconds;
    private int maxPromptLength;
    private String fallback;
    private String locale;
    private boolean poolEnabled;
    private int poolMaxTotalPrompts;
    private boolean poolPersist;
    private int poolSaveDelaySeconds;
    private List<PoolEntry> poolEntries;
    private boolean prewarmEnabled;
    private Duration prewarmRefreshBeforeTtl;
    private List<String> prewarmPrompts;
    private Map<String, ProviderSettings> providers = Map.of();
    private List<QueueEntryConfig> modelQueue = List.of();
    private int modelQueueRemainingThreshold;
    private QueueStrategy modelQueueStrategy = QueueStrategy.FAILOVER;
    private String modelQueueStrategyWarning;
    private FallbackModel fallbackModel = FallbackModel.none();
    private int knowledgeMaxChars = 6000;
    private int knowledgeMaxFileChars = 4000;
    private String defaultFormatId = FormatPresets.SIMPLE;
    private Map<String, FormatPreset> formats = Map.of();
    private io.github.neareststep.nexusai.dialogue.DialogueSettings dialogueSettings =
            io.github.neareststep.nexusai.dialogue.DialogueSettings.defaults();
    private ModerationSettings moderation = ModerationSettings.defaults();
    private io.github.neareststep.nexusai.context.ContextSettings contextSettings =
            io.github.neareststep.nexusai.context.ContextSettings.defaults();
    private int httpMaxInFlight = io.github.neareststep.nexusai.ai.HttpPool.MAX_IN_FLIGHT;
    private int httpQueueSize = io.github.neareststep.nexusai.ai.HttpPool.WAIT_QUEUE_CAPACITY;
    private final List<String> httpLimitWarnings = new ArrayList<>();
    private final List<String> shortKeyWarnings = new ArrayList<>();
    private boolean pluginApiEnabled = true;
    private int pluginApiMaxTemplateChars = 8000;
    private int pluginApiMaxVarChars = 1000;
    private final List<String> pluginApiWarnings = new ArrayList<>();
    private static final Pattern QUOTA_GROUP_NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Set<String> QUOTA_FIELDS = Set.of("tokens-per-day", "requests-per-day");

    private MissingUsage missingUsage = MissingUsage.ESTIMATE;
    private int tokenSaveIntervalSeconds = 10;
    private boolean quotasEnabled;
    private long serverTokensPerDay;
    private long playerTokensPerDay;
    private Map<String, QuotaSettings.GroupLimit> quotaGroups = Map.of();
    private Map<String, QuotaSettings.ConsumerLimit> quotaConsumers = Map.of();
    private final List<String> quotaWarnings = new ArrayList<>();

    public PluginConfig(FileConfiguration config) {
        reload(config);
    }

    public void reload(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        missingEnvVars.clear();
        keyFileWarnings.clear();
        shortKeyWarnings.clear();
        httpLimitWarnings.clear();
        pluginApiWarnings.clear();
        quotaWarnings.clear();

        this.provider = config.getString("api.provider", "openai").trim().toLowerCase(Locale.ROOT);
        this.model = config.getString("api.model", "gpt-4o-mini");
        this.baseUrl = resolveBaseUrl(provider, substitute(config.getString("api.base-url", "")));
        this.systemPrompt = blankToNull(config.getString("api.system-prompt", ""));
        double temperatureRaw = config.getDouble("api.temperature", -1.0d);
        this.temperature = temperatureRaw < 0 ? null : temperatureRaw;
        int maxTokensRaw = config.getInt("api.max-tokens", DEFAULT_MAX_TOKENS);
        this.maxTokens = maxTokensRaw <= 0 ? null : maxTokensRaw;
        this.stripMarkdown = config.getBoolean("api.strip-markdown", false);
        this.allowMarkup = config.getBoolean("sanitize.allow-markup", false);
        this.maxAnswerChars = Math.max(0, config.getInt("api.max-answer-chars", 0));
        this.maxAnswerLines = Math.max(0, config.getInt("api.max-answer-lines", 0));
        this.reasoningEffort = normalizeEffort(config.getString("api.reasoning-effort", "low"));
        this.connectTimeout = Duration.ofSeconds(Math.max(1, config.getInt("api.connect-timeout", 5)));
        this.readTimeout = Duration.ofSeconds(Math.max(1, config.getInt("api.read-timeout", 30)));
        this.cacheTtl = Duration.ofSeconds(Math.max(1, config.getInt("cache.ttl", 300)));
        this.cacheMaxSize = Math.max(1L, config.getLong("cache.max-size", 1000L));
        this.requestsHeld = false;
        this.requestsPerMinute = Math.max(1, config.getInt("limits.requests-per-minute", 30));
        this.requestsPerDay = Math.max(1, config.getInt("limits.requests-per-day", 1000));
        this.playerRequestsPerMinute = Math.max(1, config.getInt("limits.player-requests-per-minute", 10));
        this.playerRequestsPerDay = Math.max(1, config.getInt("limits.player-requests-per-day", 200));
        this.providerPauseSeconds = clamp(config.getInt("limits.provider-pause-seconds", 60), 1, 86_400);
        this.authPauseSeconds = clamp(config.getInt("limits.auth-pause-seconds", 300), 1, 86_400);
        this.errorBackoffInitialSeconds = clamp(config.getInt("limits.error-backoff-initial-seconds", 2), 0, 86_400);
        this.errorBackoffMaxSeconds = Math.max(
                this.errorBackoffInitialSeconds,
                clamp(config.getInt("limits.error-backoff-max-seconds", 120), 0, 86_400));
        this.errorLogCooldownSeconds = clamp(config.getInt("limits.error-log-cooldown-seconds", 30), 1, 86_400);
        this.maxPromptLength = Math.max(1, config.getInt("limits.max-prompt-length", 128));
        this.fallback = config.getString("fallback", "...");
        this.locale = normalizeLocaleCode(config.getString("locale", "en"));

        this.poolEnabled = config.getBoolean("pool.enabled", true);
        this.poolMaxTotalPrompts = Math.max(0, config.getInt("pool.max-total-prompts", 10));
        this.poolPersist = config.getBoolean("pool.persist", true);
        this.poolSaveDelaySeconds = clamp(config.getInt("pool.save-delay-seconds", 2), 1, 3_600);
        this.poolEntries = Collections.unmodifiableList(loadPoolEntries(config));

        this.prewarmEnabled = config.getBoolean("prewarm.enabled", true);
        this.prewarmRefreshBeforeTtl = Duration.ofSeconds(
                Math.max(1, config.getInt("prewarm.refresh-before-ttl", 60)));
        this.prewarmPrompts = Collections.unmodifiableList(loadPrewarmPrompts(config));

        String yamlKey = config.getString("api.key", "");
        yamlKey = yamlKey == null ? "" : yamlKey.trim();
        String envKey = environment.apply(ENV_API_KEY);
        if (config.isConfigurationSection("providers")) {
            this.providers = loadProviders(config, yamlKey, envKey);
            this.modelQueue = loadModelQueue(config);
            applyActiveProvider();
        } else {
            KeySource source = KeySource.CONFIG;
            if (envKey != null && !envKey.isBlank()) {
                this.apiKey = envKey.trim();
                source = KeySource.ENV;
            } else {
                this.apiKey = substitute(yamlKey).trim();
                if (EnvSubstitutor.referencesEnv(yamlKey)) {
                    source = KeySource.ENV;
                }
            }
            this.providers = Map.of(provider, new ProviderSettings(
                    provider,
                    ProviderCatalog.typeFor(provider),
                    baseUrl,
                    apiKey.isBlank() ? List.of() : List.of(apiKey),
                    source));
            this.modelQueue = loadModelQueue(config);
        }
        this.modelQueueRemainingThreshold = Math.max(0, config.getInt("model-queue-remaining-threshold", 0));
        String strategyRaw = config.getString("model-queue-strategy", "failover");
        QueueStrategy parsedStrategy = QueueStrategy.parse(strategyRaw);
        if (parsedStrategy == null) {
            this.modelQueueStrategy = QueueStrategy.FAILOVER;
            this.modelQueueStrategyWarning = "Unknown model-queue-strategy '" + strategyRaw
                    + "'. Using failover.";
        } else {
            this.modelQueueStrategy = parsedStrategy;
            this.modelQueueStrategyWarning = null;
        }
        this.fallbackModel = FallbackModel.of(
                config.getString("fallback-model.provider", ""),
                config.getString("fallback-model.model", ""));
        this.knowledgeMaxChars = positiveOrDefault(config.getInt("knowledge.max-chars", 6000), 6000);
        this.knowledgeMaxFileChars = positiveOrDefault(config.getInt("knowledge.max-file-chars", 4000), 4000);
        this.defaultFormatId = normalizeConfiguredFormat(config.getString("formats.default", FormatPresets.SIMPLE));
        this.formats = loadFormats(config);
        this.dialogueSettings = io.github.neareststep.nexusai.dialogue.DialogueSettings.read(config);
        this.moderation = ModerationSettings.load(config);
        this.contextSettings = io.github.neareststep.nexusai.context.ContextSettings.read(config);
        readHttpLimits(config);
        readPluginApi(config);
        readQuotas(config);
        noteShortKeys();
    }

    private void readQuotas(FileConfiguration config) {
        String raw = config.getString("quotas.missing-usage", "estimate");
        MissingUsage parsed = MissingUsage.parse(raw);
        if (parsed == null) {
            this.missingUsage = MissingUsage.ESTIMATE;
            quotaWarnings.add("Unknown quotas.missing-usage '" + raw + "'. Using estimate.");
        } else {
            this.missingUsage = parsed;
        }
        int interval = config.getInt("quotas.save-interval-seconds", 10);
        int clamped = interval < 1 ? 1 : Math.min(interval, 300);
        if (clamped != interval) {
            quotaWarnings.add("quotas.save-interval-seconds is " + interval
                    + ". It must be from 1 to 300. Using " + clamped + ".");
        }
        this.tokenSaveIntervalSeconds = clamped;
        this.quotasEnabled = config.getBoolean("quotas.enabled", false);
        this.serverTokensPerDay = nonNegativeQuota(config, "quotas.server-tokens-per-day");
        this.playerTokensPerDay = nonNegativeQuota(config, "quotas.player-tokens-per-day");
        this.quotaGroups = readQuotaGroups(config.getConfigurationSection("quotas.groups"));
        this.quotaConsumers = readQuotaConsumers(config.getConfigurationSection("quotas.consumers"));
    }

    private long nonNegativeQuota(FileConfiguration config, String key) {
        if (!config.contains(key)) {
            return 0L;
        }
        long value = config.getLong(key, 0L);
        if (value < 0L) {
            quotaWarnings.add(key + " is " + value + ". A negative limit is treated as 0.");
            return 0L;
        }
        return value;
    }

    private Map<String, QuotaSettings.GroupLimit> readQuotaGroups(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, QuotaSettings.GroupLimit> groups = new LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            if (name == null || !QUOTA_GROUP_NAME.matcher(name).matches()) {
                quotaWarnings.add("quotas.groups." + name + " is not a valid group name. It was skipped.");
                continue;
            }
            ConfigurationSection one = section.getConfigurationSection(name);
            if (one == null) {
                quotaWarnings.add("quotas.groups." + name + " is not a section. It was skipped.");
                continue;
            }
            warnUnknownQuotaKeys(one, "quotas.groups." + name);
            groups.put(name, new QuotaSettings.GroupLimit(
                    nonNegativeQuota(one, "tokens-per-day", "quotas.groups." + name + ".tokens-per-day"),
                    nonNegativeQuota(one, "requests-per-day", "quotas.groups." + name + ".requests-per-day")));
        }
        return groups.isEmpty() ? Map.of() : Map.copyOf(groups);
    }

    private Map<String, QuotaSettings.ConsumerLimit> readQuotaConsumers(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, QuotaSettings.ConsumerLimit> consumers = new LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            if (name == null || name.isBlank()) {
                continue;
            }
            ConfigurationSection one = section.getConfigurationSection(name);
            if (one == null) {
                quotaWarnings.add("quotas.consumers." + name + " is not a section. It was skipped.");
                continue;
            }
            warnUnknownQuotaKeys(one, "quotas.consumers." + name);
            consumers.put(name, new QuotaSettings.ConsumerLimit(
                    nonNegativeQuota(one, "tokens-per-day", "quotas.consumers." + name + ".tokens-per-day"),
                    nonNegativeQuota(one, "requests-per-day", "quotas.consumers." + name + ".requests-per-day")));
        }
        return consumers.isEmpty() ? Map.of() : Map.copyOf(consumers);
    }

    private void warnUnknownQuotaKeys(ConfigurationSection section, String path) {
        for (String key : section.getKeys(false)) {
            if (key != null && !QUOTA_FIELDS.contains(key)) {
                quotaWarnings.add("Unknown key " + path + "." + key + ". It was ignored.");
            }
        }
    }

    private long nonNegativeQuota(ConfigurationSection section, String key, String path) {
        if (!section.contains(key)) {
            return 0L;
        }
        long value = section.getLong(key, 0L);
        if (value < 0L) {
            quotaWarnings.add(path + " is " + value + ". A negative limit is treated as 0.");
            return 0L;
        }
        return value;
    }

    private void readPluginApi(FileConfiguration config) {
        this.pluginApiEnabled = config.getBoolean("plugin-api.enabled", true);
        this.pluginApiMaxTemplateChars = clampPluginApi(config, "plugin-api.max-template-chars", 8000, 100, 100_000);
        this.pluginApiMaxVarChars = clampPluginApi(config, "plugin-api.max-var-chars", 1000, 10, 20_000);
    }

    private int clampPluginApi(FileConfiguration config, String key, int fallback, int min, int max) {
        int value = config.getInt(key, fallback);
        int clamped = clamp(value, min, max);
        if (clamped != value) {
            pluginApiWarnings.add(key + " is " + value + ". It must be from " + min + " to " + max + ". Using " + clamped + ".");
        }
        return clamped;
    }

    private void readHttpLimits(FileConfiguration config) {
        this.httpMaxInFlight = positiveHttp(
                config, "http.max-in-flight", io.github.neareststep.nexusai.ai.HttpPool.MAX_IN_FLIGHT);
        this.httpQueueSize = positiveHttp(
                config, "http.queue-size", io.github.neareststep.nexusai.ai.HttpPool.WAIT_QUEUE_CAPACITY);
    }

    private int positiveHttp(FileConfiguration config, String key, int fallback) {
        int value = config.getInt(key, fallback);
        if (value > 0) {
            return value;
        }
        httpLimitWarnings.add(key + " is " + value + ". It must be greater than 0. Using " + fallback + ".");
        return fallback;
    }

    private void noteShortKeys() {
        if (providers == null) {
            return;
        }
        for (ProviderSettings settings : providers.values()) {
            boolean noted = false;
            for (String key : settings.apiKeys()) {
                if (key == null) {
                    continue;
                }
                String trimmed = key.trim();
                if (trimmed.isEmpty() || trimmed.length() > SecretMask.SUFFIX_LENGTH || noted) {
                    continue;
                }
                noted = true;
                shortKeyWarnings.add("providers." + settings.id()
                        + " has an API key of " + trimmed.length()
                        + " characters. A key this short is masked as **** and cannot show a suffix.");
            }
        }
    }

    private Map<String, ProviderSettings> loadProviders(FileConfiguration config, String legacyKey, String envKey) {
        ConfigurationSection section = config.getConfigurationSection("providers");
        Map<String, ProviderSettings> loaded = new LinkedHashMap<>();
        if (section == null) {
            return Map.of();
        }
        for (String id : section.getKeys(false)) {
            if (id == null || id.isBlank()) {
                continue;
            }
            ConfigurationSection one = section.getConfigurationSection(id);
            if (one == null) {
                continue;
            }
            String normalizedId = id.trim().toLowerCase(Locale.ROOT);
            String type = ProviderCatalog.normalizeType(one.getString("type"), normalizedId);
            String url = substitute(one.getString("url", "")).trim();
            if (url.isBlank()) {
                url = ProviderCatalog.officialUrl(normalizedId);
            } else {
                url = trimTrailingSlash(url);
            }
            boolean active = normalizedId.equals(provider);
            ResolvedKeys resolved = resolveKeys(normalizedId, one, active, legacyKey, envKey);
            loaded.put(normalizedId, new ProviderSettings(
                    normalizedId, type, url, resolved.keys(), resolved.source()));
        }
        return Map.copyOf(loaded);
    }

    private void applyActiveProvider() {
        ProviderSettings active = providers.get(provider);
        if (active == null) {
            for (QueueEntryConfig entry : modelQueue) {
                ProviderSettings candidate = providers.get(entry.provider());
                if (candidate != null) {
                    active = candidate;
                    this.provider = candidate.id();
                    break;
                }
            }
        }
        if (active == null && !providers.isEmpty()) {
            active = providers.values().iterator().next();
            this.provider = active.id();
        }
        if (active == null) {
            return;
        }
        this.baseUrl = active.url();
        this.apiKey = active.apiKeys().isEmpty() ? "" : active.apiKeys().getFirst();
    }

    private List<QueueEntryConfig> loadModelQueue(FileConfiguration config) {
        if (!config.contains("model-queue")) {
            return List.of(new QueueEntryConfig(provider, model, 0));
        }
        List<QueueEntryConfig> entries = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("model-queue")) {
            Object providerValue = map.get("provider");
            Object modelValue = map.get("model");
            if (providerValue == null || modelValue == null) {
                continue;
            }
            String providerId = String.valueOf(providerValue).trim().toLowerCase(Locale.ROOT);
            String modelId = String.valueOf(modelValue).trim();
            if (providerId.isEmpty() || modelId.isEmpty()) {
                continue;
            }
            int limit = Math.max(0, toInt(map.get("daily-request-limit"), 0));
            int tokenLimit = nonNegativeTokenLimit(map.get("daily-token-limit"), providerId, modelId);
            entries.add(new QueueEntryConfig(providerId, modelId, limit, tokenLimit));
        }
        if (entries.isEmpty()) {
            return List.of(new QueueEntryConfig(provider, model, 0));
        }
        return List.copyOf(entries);
    }

    private Map<String, FormatPreset> loadFormats(FileConfiguration config) {
        Map<String, FormatPreset> loaded = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("formats");
        for (String id : FormatPresets.IDS) {
            FormatPreset builtin = FormatPresets.builtin(id);
            ConfigurationSection one = section == null ? null : section.getConfigurationSection(id);
            if (one == null) {
                loaded.put(id, builtin);
                continue;
            }
            loaded.put(id, new FormatPreset(
                    id,
                    one.getString("instruction", builtin.instruction()),
                    one.getInt("max-lines", builtin.maxLines()),
                    one.getInt("max-chars", builtin.maxChars()),
                    one.getInt("max-chars-per-line", builtin.maxCharsPerLine()),
                    one.getInt("max-words", builtin.maxWords()),
                    one.getInt("max-sentences", builtin.maxSentences()),
                    one.getBoolean("strip-markdown", builtin.stripMarkdown()),
                    one.getBoolean("strip-trailing-punctuation", builtin.stripTrailingPunctuation())
            ));
        }
        return Map.copyOf(loaded);
    }

    private String normalizeConfiguredFormat(String raw) {
        String id = FormatPresets.normalize(raw);
        return FormatPresets.known(id) ? id : FormatPresets.SIMPLE;
    }

    private ResolvedKeys resolveKeys(
            String providerId,
            ConfigurationSection one,
            boolean active,
            String legacyKey,
            String envKey
    ) {
        Object raw = one.get("api-key");
        String filePath = one.getString("api-key-file", "");
        filePath = filePath == null ? "" : filePath.trim();
        if (!filePath.isEmpty()) {
            if (rawKeyPresent(raw)) {
                keyFileWarnings.add("providers." + providerId
                        + ".api-key is ignored because api-key-file is set.");
            }
            KeyFiles.Loaded file = KeyFiles.read(resolveSecretPath(filePath));
            keyFileWarnings.addAll(file.warnings());
            if (!file.keys().isEmpty()) {
                return new ResolvedKeys(file.keys(), KeySource.FILE);
            }
            if (!active) {
                return new ResolvedKeys(List.of(), KeySource.FILE);
            }
            return activeFallback(legacyKey, envKey, KeySource.FILE);
        }
        boolean multi = raw instanceof List<?> list && list.size() > 1;
        boolean explicit = referencesEnv(raw);
        List<String> configured = readKeyList(raw);
        if (active && !multi && !explicit && envKey != null && !envKey.isBlank()) {
            return new ResolvedKeys(List.of(envKey.trim()), KeySource.ENV);
        }
        if (!configured.isEmpty()) {
            return new ResolvedKeys(configured, explicit ? KeySource.ENV : KeySource.CONFIG);
        }
        if (!active) {
            return new ResolvedKeys(List.of(), KeySource.CONFIG);
        }
        return activeFallback(legacyKey, envKey, KeySource.CONFIG);
    }

    /**
     * Used when the chosen source produced no keys. {@code NEXUSAI_API_KEY} still fills the
     * active provider. An explicit {@code api-key-file} does not revive a neighbouring {@code api-key}.
     */
    private ResolvedKeys activeFallback(String legacyKey, String envKey, KeySource emptySource) {
        if (legacyKey != null && !legacyKey.isBlank()) {
            if (envKey != null && !envKey.isBlank() && !EnvSubstitutor.referencesEnv(legacyKey)) {
                return new ResolvedKeys(List.of(envKey.trim()), KeySource.ENV);
            }
            String substituted = substitute(legacyKey).trim();
            if (!substituted.isBlank()) {
                KeySource source = EnvSubstitutor.referencesEnv(legacyKey) ? KeySource.ENV : KeySource.CONFIG;
                return new ResolvedKeys(List.of(substituted), source);
            }
        }
        if (envKey != null && !envKey.isBlank()) {
            return new ResolvedKeys(List.of(envKey.trim()), KeySource.ENV);
        }
        return new ResolvedKeys(List.of(), emptySource);
    }

    private static boolean rawKeyPresent(Object raw) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    return true;
                }
            }
            return false;
        }
        return raw != null && !String.valueOf(raw).isBlank();
    }

    static Path resolveSecretPath(String configured) {
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path;
        }
        Path base = secretsBase;
        if (base == null) {
            return path;
        }
        return base.resolve(path).normalize();
    }

    private record ResolvedKeys(List<String> keys, KeySource source) {
    }

    private static boolean referencesEnv(Object raw) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && EnvSubstitutor.referencesEnv(String.valueOf(item))) {
                    return true;
                }
            }
            return false;
        }
        return raw != null && EnvSubstitutor.referencesEnv(String.valueOf(raw));
    }

    private List<String> readKeyList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> keys = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String value = substitute(String.valueOf(item)).trim();
                if (!value.isEmpty()) {
                    keys.add(value);
                }
            }
            return keys;
        }
        if (raw == null) {
            return List.of();
        }
        String value = substitute(String.valueOf(raw)).trim();
        if (value.isEmpty()) {
            return List.of();
        }
        return List.of(value);
    }

    private List<PoolEntry> loadPoolEntries(FileConfiguration config) {
        List<PoolEntry> entries = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("pool.entries")) {
            if (poolMaxTotalPrompts > 0 && entries.size() >= poolMaxTotalPrompts) {
                break;
            }
            Object promptObj = map.get("prompt");
            if (promptObj == null) {
                continue;
            }
            String prompt = String.valueOf(promptObj).trim();
            if (prompt.isEmpty()) {
                continue;
            }
            int size = Math.max(1, toInt(map.get("size"), 3));
            int minThreshold = Math.max(0, toInt(map.get("min-threshold"), 1));
            if (minThreshold > size) {
                minThreshold = size;
            }
            entries.add(new PoolEntry(prompt, size, minThreshold, loadVars(map.get("vars")), overridesFrom(map)));
        }
        return entries;
    }

    private static GenerationOverrides overridesFrom(Map<?, ?> map) {
        boolean systemSet = map.containsKey("system-prompt");
        String system = null;
        if (systemSet && map.get("system-prompt") != null) {
            system = String.valueOf(map.get("system-prompt"));
        }
        boolean temperatureSet = map.containsKey("temperature");
        Double temperature = null;
        if (temperatureSet) {
            temperature = toDouble(map.get("temperature"));
        }
        boolean maxTokensSet = map.containsKey("max-tokens");
        Integer maxTokens = null;
        if (maxTokensSet) {
            int parsed = toInt(map.get("max-tokens"), 0);
            maxTokens = parsed <= 0 ? null : parsed;
        }
        boolean modelSet = map.containsKey("model");
        String model = null;
        if (modelSet && map.get("model") != null) {
            model = String.valueOf(map.get("model")).trim();
            if (model.isEmpty()) {
                modelSet = false;
            }
        }
        return GenerationOverrides.of(
                systemSet, system, temperatureSet, temperature, maxTokensSet, maxTokens, modelSet, model);
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeEffort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("off") || normalized.equals("none") || normalized.equals("false")) {
            return null;
        }
        return normalized;
    }

    private static Map<String, String> loadVars(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).trim();
            String value = String.valueOf(entry.getValue()).trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            vars.put(key, value);
        }
        return vars;
    }

    private static List<String> loadPrewarmPrompts(FileConfiguration config) {
        List<String> prompts = new ArrayList<>();
        for (String raw : config.getStringList("prewarm.prompts")) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                prompts.add(trimmed);
            }
        }
        return prompts;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private int nonNegativeTokenLimit(Object value, String providerId, String modelId) {
        if (value == null) {
            return 0;
        }
        long parsed = toLong(value, 0L);
        if (parsed < 0L) {
            quotaWarnings.add("model-queue " + providerId + "/" + modelId
                    + " daily-token-limit is " + parsed + ". A negative limit is treated as 0.");
            return 0;
        }
        if (parsed > Integer.MAX_VALUE) {
            quotaWarnings.add("model-queue " + providerId + "/" + modelId
                    + " daily-token-limit is " + parsed + ". Using " + Integer.MAX_VALUE + ".");
            return Integer.MAX_VALUE;
        }
        return (int) parsed;
    }

    private static long toLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static String resolveBaseUrl(String provider, String configured) {
        if (configured != null && !configured.isBlank()) {
            return trimTrailingSlash(configured.trim());
        }
        String defaults = PROVIDER_BASE_URLS.getOrDefault(
                provider == null ? "openai" : provider.toLowerCase(Locale.ROOT),
                PROVIDER_BASE_URLS.get("openai"));
        return trimTrailingSlash(defaults);
    }

    private static String normalizeLocaleCode(String requested) {
        if (requested == null || requested.isBlank()) {
            return "en";
        }
        return io.github.neareststep.nexusai.i18n.MessageService.normalizeLocale(requested);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return PROVIDER_BASE_URLS.get("openai");
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    /**
     * When true, model replies may keep {@code &#RRGGBB} and MiniMessage tags for placeholder
     * consumers. Legacy {@code §} and {@code &} codes are still removed. Default is false.
     */
    public boolean allowMarkup() {
        return allowMarkup;
    }

    public boolean isStripMarkdown() {
        return stripMarkdown;
    }

    public int getMaxAnswerChars() {
        return maxAnswerChars;
    }

    public int getMaxAnswerLines() {
        return maxAnswerLines;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Requests may be sent when at least one model-queue row, the fallback model, or an enabled
     * pinned moderation provider can accept a call. A target can accept a call when it has an API
     * key or when its endpoint does not need one (Ollama, or a localhost / port 11434 base URL).
     * An empty key on {@code api.provider} does not block a different row that can send.
     * Pinned moderation alone does not let placeholders or {@code /nai test} send; see
     * {@link #canSendChatRequests()}.
     */
    public boolean canSendRequests() {
        if (canSendChatRequests()) {
            return true;
        }
        if (requestsHeld) {
            return false;
        }
        ModerationSettings pinned = moderation == null ? ModerationSettings.defaults() : moderation;
        return pinned.enabled() && pinned.pinned() && providerUsable(provider(pinned.provider()));
    }

    /**
     * Placeholders, the pool, prewarm, dialogue, and {@code /nai test} can send.
     * A pinned moderation provider is not one of these targets.
     */
    public boolean canSendChatRequests() {
        if (requestsHeld) {
            return false;
        }
        for (QueueEntryConfig entry : modelQueue) {
            if (providerUsable(provider(entry.provider()))) {
                return true;
            }
        }
        FallbackModel fallback = fallbackModel();
        return fallback.configured() && providerUsable(provider(fallback.provider()));
    }

    /**
     * One startup and {@code /nai reload} warning when {@code api.max-tokens} omits the field
     * and groq is in the model queue or is the fallback model. Null when the cap is positive
     * or groq is not used. Groq counts a request with no {@code max_tokens} against a small
     * output-token budget.
     */
    public String groqUnlimitedOutputWarning() {
        if (maxTokens != null) {
            return null;
        }
        boolean queued = false;
        for (QueueEntryConfig entry : modelQueue) {
            if (entry != null && "groq".equals(entry.provider())) {
                queued = true;
                break;
            }
        }
        boolean fallbackGroq = "groq".equals(fallbackModel().provider());
        if (!queued && !fallbackGroq) {
            return null;
        }
        String where;
        if (queued && fallbackGroq) {
            where = "A groq provider is in the model queue and is the fallback model";
        } else if (queued) {
            where = "A groq provider is in the model queue";
        } else {
            where = "A groq provider is the fallback model";
        }
        return "api.max-tokens is 0 or negative, so max_tokens is omitted. "
                + where + " and can hit HTTP 429 "
                + "(output tokens per minute) after a long reply, then pause. "
                + "Set api.max-tokens to 256, or set 0 again only if you want the field left off.";
    }

    /**
     * Startup warning when chat requests cannot be sent, or null when they can.
     * A config whose only usable target is pinned moderation gets its own warning.
     */
    public String credentialWarning() {
        if (requestsHeld || canSendChatRequests()) {
            return null;
        }
        if (canSendRequests()) {
            return "API key is not set for the model queue or fallback model. "
                    + "Placeholders, the pool, prewarm, and /nai test will not be sent. "
                    + "Pinned moderation can still run.";
        }
        return "API key is not set (env NEXUSAI_API_KEY or api.key). "
                + "Plugin will load, but AI requests will not be sent.";
    }

    /**
     * Key presence for the active provider, each model-queue provider, the fallback model, and a
     * pinned moderation provider. {@code yes} and {@code no} are the localized words.
     * A provider that can send without a key is {@code local}.
     */
    public String providerKeyPresence(String yes, String no) {
        String present = yes == null ? "yes" : yes;
        String absent = no == null ? "no" : no;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (provider != null && !provider.isBlank()) {
            ids.add(provider);
        }
        for (QueueEntryConfig entry : modelQueue) {
            ids.add(entry.provider());
        }
        FallbackModel fallback = fallbackModel();
        if (fallback.configured()) {
            ids.add(fallback.provider());
        }
        if (moderation != null && moderation.pinned()) {
            ids.add(moderation.provider());
        }
        StringBuilder line = new StringBuilder();
        for (String id : ids) {
            if (!line.isEmpty()) {
                line.append(", ");
            }
            line.append(id).append(": ").append(keyPresence(providers.get(id), present, absent));
        }
        return line.toString();
    }

    private String keyPresence(ProviderSettings settings, String present, String absent) {
        if (settings != null && settings.hasKeys()) {
            return present;
        }
        if (providerAllowsKeyless(settings)) {
            return "local";
        }
        return absent;
    }

    private boolean providerUsable(ProviderSettings candidate) {
        return candidate != null && (candidate.hasKeys() || providerAllowsKeyless(candidate));
    }

    /**
     * A syntax error in {@code config.yml} must not fall through to the jar defaults and a live API key.
     */
    public void holdRequests() {
        this.requestsHeld = true;
    }

    public boolean requestsHeld() {
        return requestsHeld;
    }

    public boolean allowsKeylessRequests() {
        return "ollama".equals(provider) || isLocalBaseUrl(baseUrl);
    }

    static boolean isLocalBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost();
            if (host != null) {
                String normalized = host.toLowerCase(Locale.ROOT);
                if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
                    normalized = normalized.substring(1, normalized.length() - 1);
                }
                if (normalized.equals("localhost")
                        || normalized.equals("127.0.0.1")
                        || normalized.equals("0.0.0.0")
                        || normalized.equals("::1")
                        || normalized.endsWith(".local")) {
                    return true;
                }
            }
            return uri.getPort() == 11434;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public long getCacheMaxSize() {
        return cacheMaxSize;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public int getRequestsPerDay() {
        return requestsPerDay;
    }

    public int getPlayerRequestsPerMinute() {
        return playerRequestsPerMinute;
    }

    public int getPlayerRequestsPerDay() {
        return playerRequestsPerDay;
    }

    public int getProviderPauseSeconds() {
        return providerPauseSeconds;
    }

    public int getAuthPauseSeconds() {
        return authPauseSeconds;
    }

    public int getErrorBackoffInitialSeconds() {
        return errorBackoffInitialSeconds;
    }

    public int getErrorBackoffMaxSeconds() {
        return errorBackoffMaxSeconds;
    }

    public int getErrorLogCooldownSeconds() {
        return errorLogCooldownSeconds;
    }

    public int getMaxPromptLength() {
        return maxPromptLength;
    }

    public String getFallback() {
        return fallback;
    }

    public String getLocale() {
        return locale;
    }

    public boolean isPoolEnabled() {
        return poolEnabled;
    }

    public int getPoolMaxTotalPrompts() {
        return poolMaxTotalPrompts;
    }

    public boolean isPoolPersist() {
        return poolPersist;
    }

    public int getPoolSaveDelaySeconds() {
        return poolSaveDelaySeconds;
    }

    public List<PoolEntry> getPoolEntries() {
        return poolEntries;
    }

    public boolean isPrewarmEnabled() {
        return prewarmEnabled;
    }

    public Duration getPrewarmRefreshBeforeTtl() {
        return prewarmRefreshBeforeTtl;
    }

    public List<String> getPrewarmPrompts() {
        return prewarmPrompts;
    }

    public ModerationSettings moderation() {
        return moderation;
    }

    public Map<String, ProviderSettings> providers() {
        return providers;
    }

    public ProviderSettings provider(String id) {
        if (id == null) {
            return null;
        }
        return providers.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public boolean providerAllowsKeyless(ProviderSettings candidate) {
        return candidate != null && ("ollama".equals(candidate.id()) || isLocalBaseUrl(candidate.url()));
    }

    public List<QueueEntryConfig> modelQueue() {
        return modelQueue;
    }

    public int modelQueueRemainingThreshold() {
        return modelQueueRemainingThreshold;
    }

    public QueueStrategy modelQueueStrategy() {
        return modelQueueStrategy == null ? QueueStrategy.FAILOVER : modelQueueStrategy;
    }

    /**
     * Non-null when {@code model-queue-strategy} is not {@code failover} or {@code round-robin}.
     * The plugin logs it once per startup and per {@code /nai reload}.
     */
    public String modelQueueStrategyWarning() {
        return modelQueueStrategyWarning;
    }

    public FallbackModel fallbackModel() {
        return fallbackModel == null ? FallbackModel.none() : fallbackModel;
    }

    public int knowledgeMaxChars() {
        return knowledgeMaxChars;
    }

    public int knowledgeMaxFileChars() {
        return knowledgeMaxFileChars;
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    public String defaultFormatId() {
        return defaultFormatId;
    }

    public String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return defaultFormatId;
        }
        String id = FormatPresets.normalize(format);
        return FormatPresets.known(id) ? id : defaultFormatId;
    }

    public FormatPreset presetFor(String id) {
        String normalized = normalizeFormat(id);
        FormatPreset configured = formats.get(normalized);
        return configured == null ? FormatPresets.builtin(normalized) : configured;
    }

    public io.github.neareststep.nexusai.dialogue.DialogueSettings dialogueSettings() {
        return dialogueSettings;
    }

    public io.github.neareststep.nexusai.context.ContextSettings contextSettings() {
        return contextSettings == null
                ? io.github.neareststep.nexusai.context.ContextSettings.defaults()
                : contextSettings;
    }

    /**
     * Names of {@code ${ENV_VAR}} placeholders whose variable was unset at the last reload.
     * The placeholder text itself is not a key and is not returned.
     */
    public List<String> missingEnvVars() {
        return List.copyOf(missingEnvVars);
    }

    /**
     * Warnings from {@code api-key-file} on the last reload. Paths may appear. Key text does not.
     */
    public List<String> keyFileWarnings() {
        return List.copyOf(keyFileWarnings);
    }

    /**
     * Resolved secrets, including keys too short to show a suffix. Used only to strip them from
     * command text and error bodies. The key text is not logged from here.
     */
    public List<String> configuredSecrets() {
        List<String> secrets = new ArrayList<>();
        if (providers != null) {
            for (ProviderSettings settings : providers.values()) {
                for (String key : settings.apiKeys()) {
                    if (key == null) {
                        continue;
                    }
                    String trimmed = key.trim();
                    if (!trimmed.isEmpty() && !secrets.contains(trimmed)) {
                        secrets.add(trimmed);
                    }
                }
            }
        }
        if (apiKey != null && !apiKey.isBlank() && !secrets.contains(apiKey.trim())) {
            secrets.add(apiKey.trim());
        }
        return secrets;
    }

    public int httpMaxInFlight() {
        return httpMaxInFlight;
    }

    public int httpQueueSize() {
        return httpQueueSize;
    }

    /**
     * One warning per key when {@code http.max-in-flight} or {@code http.queue-size} is not positive.
     * Each line names the key, the rejected value, and the value that is used. Empty when both were accepted.
     */
    public List<String> httpLimitWarnings() {
        return List.copyOf(httpLimitWarnings);
    }

    /** {@code plugin-api.enabled}. Missing means true. */
    public boolean pluginApiEnabled() {
        return pluginApiEnabled;
    }

    /** {@code plugin-api.max-template-chars}, clamped to 100..100000. */
    public int pluginApiMaxTemplateChars() {
        return pluginApiMaxTemplateChars;
    }

    /** {@code plugin-api.max-var-chars}, clamped to 10..20000. */
    public int pluginApiMaxVarChars() {
        return pluginApiMaxVarChars;
    }

    public List<String> pluginApiWarnings() {
        return List.copyOf(pluginApiWarnings);
    }

    /** {@code quotas.missing-usage}. Missing or unknown means {@link MissingUsage#ESTIMATE}. */
    public MissingUsage missingUsage() {
        return missingUsage == null ? MissingUsage.ESTIMATE : missingUsage;
    }

    /** {@code quotas.save-interval-seconds}, clamped to 1..300. */
    public int tokenSaveIntervalSeconds() {
        return tokenSaveIntervalSeconds;
    }

    public List<String> quotaWarnings() {
        return List.copyOf(quotaWarnings);
    }

    /** Daily caps. Enforcement uses this only when {@link QuotaSettings#enabled()} is true. */
    public QuotaSettings quotaSettings() {
        return new QuotaSettings(quotasEnabled, serverTokensPerDay, playerTokensPerDay, quotaGroups, quotaConsumers);
    }

    /**
     * Load warnings for keys too short to show a suffix. The key text is not included.
     */
    public List<String> shortKeyWarnings() {
        return List.copyOf(shortKeyWarnings);
    }

    private String substitute(String value) {
        return EnvSubstitutor.apply(value, environment, missingEnvVars);
    }

    public String maskedApiKeys() {
        ProviderSettings active = providers.get(provider);
        if (active == null || active.apiKeys().isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder();
        for (String key : active.apiKeys()) {
            String token = SecretMask.mask(key);
            if (token.isEmpty()) {
                continue;
            }
            if (!masked.isEmpty()) {
                masked.append(", ");
            }
            masked.append(token);
        }
        if (masked.isEmpty()) {
            return "";
        }
        return masked + active.keySource().statusSuffix();
    }
}
