package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Typed view over {@code config.yml} with environment-variable overrides for the API key.
 */
public final class PluginConfig {

    private static final String ENV_API_KEY = "NEXUSAI_API_KEY";

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

    public PluginConfig(FileConfiguration config) {
        reload(config);
    }

    public void reload(FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        this.provider = config.getString("api.provider", "openai").trim().toLowerCase(Locale.ROOT);
        this.model = config.getString("api.model", "gpt-4o-mini");
        this.baseUrl = trimTrailingSlash(config.getString("api.base-url", "https://api.openai.com/v1"));
        this.connectTimeout = Duration.ofSeconds(Math.max(1, config.getInt("api.connect-timeout", 5)));
        this.readTimeout = Duration.ofSeconds(Math.max(1, config.getInt("api.read-timeout", 30)));
        this.cacheTtl = Duration.ofSeconds(Math.max(1, config.getInt("cache.ttl", 300)));
        this.cacheMaxSize = Math.max(1L, config.getLong("cache.max-size", 1000L));
        this.requestsPerMinute = Math.max(1, config.getInt("limits.requests-per-minute", 30));
        this.requestsPerDay = Math.max(1, config.getInt("limits.requests-per-day", 1000));
        this.maxPromptLength = Math.max(1, config.getInt("limits.max-prompt-length", 128));
        this.fallback = config.getString("fallback", "...");

        String envKey = System.getenv(ENV_API_KEY);
        if (envKey != null && !envKey.isBlank()) {
            this.apiKey = envKey.trim();
        } else {
            String yamlKey = config.getString("api.key", "");
            this.apiKey = yamlKey == null ? "" : yamlKey.trim();
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com/v1";
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
}
