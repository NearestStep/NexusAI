package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Typed view over {@code config.yml} with environment-variable overrides for the API key.
 */
public final class PluginConfig {

    private static final String ENV_API_KEY = "NEXUSAI_API_KEY";

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

    public PluginConfig(FileConfiguration config) {
        reload(config);
    }

    public void reload(FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        this.provider = config.getString("api.provider", "openai").trim().toLowerCase(Locale.ROOT);
        this.model = config.getString("api.model", "gpt-4o-mini");
        this.baseUrl = resolveBaseUrl(provider, config.getString("api.base-url", ""));
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

        String envKey = System.getenv(ENV_API_KEY);
        if (envKey != null && !envKey.isBlank()) {
            this.apiKey = envKey.trim();
        } else {
            String yamlKey = config.getString("api.key", "");
            this.apiKey = yamlKey == null ? "" : yamlKey.trim();
        }
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
        return GenerationOverrides.of(systemSet, system, temperatureSet, temperature, maxTokensSet, maxTokens);
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
}
