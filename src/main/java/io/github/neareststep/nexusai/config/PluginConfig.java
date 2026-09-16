package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
            "deepseek", "https://api.deepseek.com"
    );

    private String provider;
    private String model;
    private String baseUrl;
    private String apiKey;
    private Duration connectTimeout;
    private Duration readTimeout;
    private Duration cacheTtl;
    private long cacheMaxSize;
    private int requestsPerMinute;
    private int requestsPerDay;
    private int maxPromptLength;
    private String fallback;
    private boolean poolEnabled;
    private int poolMaxTotalPrompts;
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
        this.connectTimeout = Duration.ofSeconds(Math.max(1, config.getInt("api.connect-timeout", 5)));
        this.readTimeout = Duration.ofSeconds(Math.max(1, config.getInt("api.read-timeout", 30)));
        this.cacheTtl = Duration.ofSeconds(Math.max(1, config.getInt("cache.ttl", 300)));
        this.cacheMaxSize = Math.max(1L, config.getLong("cache.max-size", 1000L));
        this.requestsPerMinute = Math.max(1, config.getInt("limits.requests-per-minute", 30));
        this.requestsPerDay = Math.max(1, config.getInt("limits.requests-per-day", 1000));
        this.maxPromptLength = Math.max(1, config.getInt("limits.max-prompt-length", 128));
        this.fallback = config.getString("fallback", "...");

        this.poolEnabled = config.getBoolean("pool.enabled", true);
        this.poolMaxTotalPrompts = Math.max(0, config.getInt("pool.max-total-prompts", 10));
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
            entries.add(new PoolEntry(prompt, size, minThreshold));
        }
        return entries;
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

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
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

    public int getMaxPromptLength() {
        return maxPromptLength;
    }

    public String getFallback() {
        return fallback;
    }

    public boolean isPoolEnabled() {
        return poolEnabled;
    }

    public int getPoolMaxTotalPrompts() {
        return poolMaxTotalPrompts;
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
