package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.placeholder.AiPlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class NexusAI extends JavaPlugin {

    private PluginConfig pluginConfig;
    private AiCache aiCache;
    private RateLimiter rateLimiter;
    private AiHttpClient aiHttpClient;
    private ExecutorService httpExecutor;
    private AiPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(getConfig());

        if (!pluginConfig.hasApiKey()) {
            getLogger().warning("API key is not set (env NEXUSAI_API_KEY or api.key). "
                    + "Plugin will load, but AI requests will not be sent.");
        }

        this.httpExecutor = createHttpExecutor();
        this.aiCache = new AiCache(pluginConfig.getCacheTtl(), pluginConfig.getCacheMaxSize());
        this.rateLimiter = new RateLimiter(pluginConfig.getRequestsPerMinute(), pluginConfig.getRequestsPerDay());

        AiProvider provider = createProvider(pluginConfig);
        this.aiHttpClient = new AiHttpClient(aiCache, provider, pluginConfig, getLogger());

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholderExpansion = new AiPlaceholderExpansion(
                    this, pluginConfig, aiCache, aiHttpClient, rateLimiter);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered PlaceholderAPI expansion %ainexus_generate_<prompt>%");
            } else {
                getLogger().warning("Failed to register PlaceholderAPI expansion.");
            }
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholders will be unavailable.");
        }

        getLogger().info("NexusAI enabled (provider=" + pluginConfig.getProvider()
                + ", model=" + pluginConfig.getModel() + ").");
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (aiCache != null) {
            aiCache.invalidateAll();
        }
        shutdownExecutor();
        getLogger().info("NexusAI disabled.");
    }

    private AiProvider createProvider(PluginConfig config) {
        String provider = config.getProvider();
        if (!"openai".equals(provider)) {
            getLogger().warning("Unknown api.provider '" + provider + "', falling back to openai.");
        }
        return new OpenAiProvider(config, httpExecutor, getLogger());
    }

    private static ExecutorService createHttpExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "nexusai-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(4, factory);
    }

    private void shutdownExecutor() {
        if (httpExecutor == null) {
            return;
        }
        httpExecutor.shutdown();
        try {
            if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
                httpExecutor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            httpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public AiCache getAiCache() {
        return aiCache;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public AiHttpClient getAiHttpClient() {
        return aiHttpClient;
    }

    public ExecutorService getHttpExecutor() {
        return httpExecutor;
    }
}
