package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.command.NaiCommand;
import io.github.neareststep.nexusai.config.ConfigMerger;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.i18n.MessageService;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.placeholder.AiPlaceholderExpansion;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import io.github.neareststep.nexusai.pool.PoolStore;
import io.github.neareststep.nexusai.prewarm.PrewarmService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import java.util.logging.Level;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class NexusAI extends JavaPlugin {

    private static final Set<String> KNOWN_PROVIDERS = Set.of(
            "openai", "groq", "cerebras", "gemini", "deepseek", "ollama", "openrouter"
    );

    private PluginConfig pluginConfig;
    private MessageService messageService;
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
        mergeMissingConfig();
        this.pluginConfig = new PluginConfig(getConfig());
        this.messageService = new MessageService(this);
        this.messageService.reload(pluginConfig.getLocale());

        if (!pluginConfig.canSendRequests()) {
            getLogger().warning("API key is not set (env NEXUSAI_API_KEY or api.key). "
                    + "Plugin will load, but AI requests will not be sent.");
        } else if (!pluginConfig.hasApiKey()) {
            getLogger().info("No API key set. Requests to this local endpoint omit the Authorization header.");
        }

        getLogger().info("Using provider: " + pluginConfig.getProvider()
                + ", base-url: " + pluginConfig.getBaseUrl()
                + ", model: " + pluginConfig.getModel());

        this.httpExecutor = createHttpExecutor();
        this.scheduler = createScheduler();
        startRuntimeServices();
        registerPlaceholderExpansion();
        registerCommands();

        getLogger().info("NexusAI enabled.");
    }

    @Override
    public void onDisable() {
        unregisterPlaceholderExpansion();
        stopRuntimeServices(true);
        shutdownExecutor(scheduler);
        shutdownExecutor(httpExecutor);
        getLogger().info("NexusAI disabled.");
    }

    /**
     * Reloads config.yml + locale, then rebuilds cache/pool/prewarm while keeping HTTP executors.
     */
    public void reloadPlugin() {
        mergeMissingConfig();
        reloadConfig();
        pluginConfig.reload(getConfig());
        messageService.reload(pluginConfig.getLocale());

        stopRuntimeServices(true);
        startRuntimeServices();
        registerPlaceholderExpansion();

        getLogger().info("NexusAI reloaded (locale=" + pluginConfig.getLocale() + ").");
    }

    private void startRuntimeServices() {
        this.aiCache = new AiCache(pluginConfig.getCacheTtl(), pluginConfig.getCacheMaxSize());
        this.rateLimiter = new RateLimiter(pluginConfig.getRequestsPerMinute(), pluginConfig.getRequestsPerDay());

        AiProvider provider = createProvider(pluginConfig);
        RequestGate gate = RequestGate.fromConfig(rateLimiter, pluginConfig);
        AiDiagnostics diagnostics = new AiDiagnostics(
                getLogger(), Duration.ofSeconds(pluginConfig.getErrorLogCooldownSeconds()));
        this.aiHttpClient = new AiHttpClient(aiCache, provider, pluginConfig, gate, diagnostics, getLogger());
        this.aiPool = new AiPool();
        PoolStore poolStore = new PoolStore(
                new File(getDataFolder(), "pool.yml"),
                scheduler,
                Duration.ofSeconds(pluginConfig.getPoolSaveDelaySeconds()),
                getLogger(),
                pluginConfig.isPoolPersist()
        );
        this.poolService = new PoolService(pluginConfig, aiPool, aiHttpClient, getLogger(), poolStore);
        this.prewarmService = new PrewarmService(
                pluginConfig, aiCache, aiHttpClient, scheduler, getLogger());

        poolService.start();
        prewarmService.start();
        prewarmService.scheduleRefresh();
    }

    private void stopRuntimeServices(boolean invalidateCache) {
        unregisterPlaceholderExpansion();
        if (prewarmService != null) {
            prewarmService.shutdown();
            prewarmService = null;
        }
        if (poolService != null) {
            poolService.shutdown();
            poolService = null;
        }
        if (invalidateCache && aiCache != null) {
            aiCache.invalidateAll();
        }
    }

    private void registerPlaceholderExpansion() {
        unregisterPlaceholderExpansion();
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholderExpansion = new AiPlaceholderExpansion(
                    this, pluginConfig, aiCache, aiHttpClient, aiPool, poolService);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered PlaceholderAPI expansion "
                        + "%ainexus_generate_<prompt>% / %ainexus_cached_<prompt>%");
            } else {
                getLogger().warning("Failed to register PlaceholderAPI expansion.");
            }
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholders will be unavailable.");
        }
    }

    private void unregisterPlaceholderExpansion() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("nai");
        if (command == null) {
            getLogger().warning("Command 'nai' missing from plugin.yml");
            return;
        }
        NaiCommand executor = new NaiCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void mergeMissingConfig() {
        saveDefaultConfig();
        File file = new File(getDataFolder(), "config.yml");
        try (InputStream in = getResource("config.yml")) {
            if (in == null || !file.isFile()) {
                return;
            }
            String defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            ConfigMerger.Result result = ConfigMerger.mergeMissing(existing, defaults);
            if (result.addedKeys().isEmpty()) {
                return;
            }
            Files.writeString(file.toPath(), result.yaml(), StandardCharsets.UTF_8);
            getLogger().info("Added missing config keys: " + String.join(", ", result.addedKeys()));
            reloadConfig();
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to merge missing config keys", e);
        }
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

    public MessageService getMessageService() {
        return messageService;
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
