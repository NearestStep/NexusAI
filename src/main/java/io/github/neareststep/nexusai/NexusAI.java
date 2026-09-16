package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.placeholder.AiPlaceholderExpansion;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import io.github.neareststep.nexusai.prewarm.PrewarmService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class NexusAI extends JavaPlugin {

    private static final Set<String> KNOWN_PROVIDERS = Set.of(
            "openai", "groq", "cerebras", "gemini", "deepseek"
    );

    private PluginConfig pluginConfig;
    private AiCache aiCache;
    private RateLimiter rateLimiter;
    private AiHttpClient aiHttpClient;
    private AiPool aiPool;
    private PoolService poolService;
    private PrewarmService prewarmService;
    private ExecutorService httpExecutor;
    private ScheduledExecutorService scheduler;
    private AiPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(getConfig());

        if (!pluginConfig.hasApiKey()) {
            getLogger().warning("API key is not set (env NEXUSAI_API_KEY or api.key). "
                    + "Plugin will load, but AI requests will not be sent.");
        }

        getLogger().info("Using provider: " + pluginConfig.getProvider()
                + ", base-url: " + pluginConfig.getBaseUrl()
                + ", model: " + pluginConfig.getModel());

        this.httpExecutor = createHttpExecutor();
        this.scheduler = createScheduler();
        this.aiCache = new AiCache(pluginConfig.getCacheTtl(), pluginConfig.getCacheMaxSize());
        this.rateLimiter = new RateLimiter(pluginConfig.getRequestsPerMinute(), pluginConfig.getRequestsPerDay());

        AiProvider provider = createProvider(pluginConfig);
        this.aiHttpClient = new AiHttpClient(aiCache, provider, pluginConfig, getLogger());
        this.aiPool = new AiPool();
        this.poolService = new PoolService(pluginConfig, aiPool, aiHttpClient, getLogger());
        this.prewarmService = new PrewarmService(
                pluginConfig, aiCache, aiHttpClient, scheduler, getLogger());

        poolService.start();
        prewarmService.start();
        prewarmService.scheduleRefresh();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholderExpansion = new AiPlaceholderExpansion(
                    this, pluginConfig, aiCache, aiHttpClient, rateLimiter, aiPool, poolService);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered PlaceholderAPI expansion "
                        + "%ainexus_generate_<prompt>% / %ainexus_cached_<prompt>%");
            } else {
                getLogger().warning("Failed to register PlaceholderAPI expansion.");
            }
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholders will be unavailable.");
        }

        getLogger().info("NexusAI enabled.");
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (prewarmService != null) {
            prewarmService.shutdown();
        }
        if (poolService != null) {
            poolService.shutdown();
        }
        if (aiCache != null) {
            aiCache.invalidateAll();
        }
        shutdownExecutor(scheduler);
        shutdownExecutor(httpExecutor);
        getLogger().info("NexusAI disabled.");
    }

    private AiProvider createProvider(PluginConfig config) {
        String provider = config.getProvider();
        if (!KNOWN_PROVIDERS.contains(provider)) {
            getLogger().warning("Unknown api.provider '" + provider + "', using OpenAI-compatible client.");
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

    private static ScheduledExecutorService createScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "nexusai-scheduler");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
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

    public AiPool getAiPool() {
        return aiPool;
    }

    public PoolService getPoolService() {
        return poolService;
    }

    public PrewarmService getPrewarmService() {
        return prewarmService;
    }

    public ExecutorService getHttpExecutor() {
        return httpExecutor;
    }
}
