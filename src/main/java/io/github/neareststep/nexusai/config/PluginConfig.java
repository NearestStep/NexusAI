package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
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

    /** Test seam. Production reads the process environment. */
    static Function<String, String> environment = System::getenv;

    private final Set<String> missingEnvVars = new LinkedHashSet<>();

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
    private String defaultFormatId = FormatPresets.SIMPLE;
    private Map<String, FormatPreset> formats = Map.of();

    public PluginConfig(FileConfiguration config) {
        reload(config);
    }

    public void reload(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        missingEnvVars.clear();

        this.provider = config.getString("api.provider", "openai").trim().toLowerCase(Locale.ROOT);
        this.model = config.getString("api.model", "gpt-4o-mini");
        this.baseUrl = resolveBaseUrl(provider, substitute(config.getString("api.base-url", "")));
        this.systemPrompt = blankToNull(config.getString("api.system-prompt", ""));
        double temperatureRaw = config.getDouble("api.temperature", -1.0d);
        this.temperature = temperatureRaw < 0 ? null : temperatureRaw;
        int maxTokensRaw = config.getInt("api.max-tokens", 0);
        this.maxTokens = maxTokensRaw <= 0 ? null : maxTokensRaw;
        this.stripMarkdown = config.getBoolean("api.strip-markdown", false);
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
            if (envKey != null && !envKey.isBlank()) {
                this.apiKey = envKey.trim();
            } else {
                this.apiKey = substitute(yamlKey).trim();
            }
            this.providers = Map.of(provider, new ProviderSettings(
                    provider,
                    ProviderCatalog.typeFor(provider),
                    baseUrl,
                    apiKey.isBlank() ? List.of() : List.of(apiKey)));
            this.modelQueue = loadModelQueue(config);
        }
        this.modelQueueRemainingThreshold = Math.max(0, config.getInt("model-queue-remaining-threshold", 0));
        this.defaultFormatId = normalizeConfiguredFormat(config.getString("formats.default", FormatPresets.SIMPLE));
        this.formats = loadFormats(config);
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
            List<String> keys = resolveKeys(one.get("api-key"), active, legacyKey, envKey);
            loaded.put(normalizedId, new ProviderSettings(normalizedId, type, url, keys));
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
            entries.add(new QueueEntryConfig(providerId, modelId, limit));
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

    private List<String> resolveKeys(Object raw, boolean active, String legacyKey, String envKey) {
        boolean multi = raw instanceof List<?> list && list.size() > 1;
        boolean explicit = referencesEnv(raw);
        List<String> configured = readKeyList(raw);
        if (active && !multi && !explicit && envKey != null && !envKey.isBlank()) {
            return List.of(envKey.trim());
        }
        if (!configured.isEmpty()) {
            return configured;
        }
        if (active && legacyKey != null && !legacyKey.isBlank()) {
            if (envKey != null && !envKey.isBlank() && !EnvSubstitutor.referencesEnv(legacyKey)) {
                return List.of(envKey.trim());
            }
            String substituted = substitute(legacyKey).trim();
            if (!substituted.isBlank()) {
                return List.of(substituted);
            }
        }
        if (active && envKey != null && !envKey.isBlank()) {
            return List.of(envKey.trim());
        }
        return List.of();
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
     * Requests may be sent when a key is configured, or when the endpoint does not need one
     * (Ollama preset, or a localhost / port 11434 base URL).
     */
    public boolean canSendRequests() {
        return !requestsHeld && (hasApiKey() || allowsKeylessRequests());
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

    /**
     * Names of {@code ${ENV_VAR}} placeholders whose variable was unset at the last reload.
     * The placeholder text itself is not a key and is not returned.
     */
    public List<String> missingEnvVars() {
        return List.copyOf(missingEnvVars);
    }

    /**
     * Resolved secrets longer than four characters. Used only to strip them from command text.
     */
    public List<String> configuredSecrets() {
        List<String> secrets = new ArrayList<>();
        if (providers != null) {
            for (ProviderSettings settings : providers.values()) {
                for (String key : settings.apiKeys()) {
                    if (key != null && key.trim().length() > 4) {
                        secrets.add(key.trim());
                    }
                }
            }
        }
        if (apiKey != null && apiKey.trim().length() > 4 && !secrets.contains(apiKey.trim())) {
            secrets.add(apiKey.trim());
        }
        return secrets;
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
        return masked.toString();
    }
}
