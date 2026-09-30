package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.ConfigMigrator;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.command.NaiCommand;
import io.github.neareststep.nexusai.config.ConfigMerger;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.i18n.MessageService;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.placeholder.AiPlaceholderExpansion;
import io.github.neareststep.nexusai.config.PoolEntry;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import io.github.neareststep.nexusai.pool.PoolStore;
import io.github.neareststep.nexusai.prewarm.PrewarmService;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private HttpClient sharedHttpClient;
    private AiPlaceholderExpansion placeholderExpansion;
    private volatile PromptCatalog promptCatalog = PromptCatalog.empty();
    private volatile ModelQueue modelQueue;
    private boolean loggedMissingKey;
    private boolean loggedMissingPapi;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs() && !getDataFolder().isDirectory()) {
            getLogger().warning("Could not create the NexusAI data folder.");
        }
        saveDefaultConfig();
        if (!new File(getDataFolder(), "prompts.yml").isFile()) {
            saveResource("prompts.yml", false);
        }
        migrateConfigs();
        if (!mergeMissingConfig()) {
            getLogger().warning("config.yml has a syntax error. The file was left unchanged, "
                    + "and AI requests stay off until a valid /nai reload.");
            this.pluginConfig = heldDefaults();
        } else {
            this.pluginConfig = new PluginConfig(getConfig());
        }
        this.messageService = new MessageService(this);
        this.messageService.reload(pluginConfig.getLocale());
        loadPrompts();
        logCredentialState();
        logMissingEnvVars();

        getLogger().info("Using provider: " + pluginConfig.getProvider()
                + ", base-url: " + pluginConfig.getBaseUrl()
                + ", model: " + pluginConfig.getModel());
        String maskedKeys = pluginConfig.maskedApiKeys();
        if (!maskedKeys.isBlank()) {
            getLogger().info("API keys: " + maskedKeys);
        }

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
        closeSharedHttpClient();
        shutdownExecutor(scheduler);
        shutdownExecutor(httpExecutor);
        getLogger().info("NexusAI disabled.");
    }

    /**
     * Reloads config.yml, prompts.yml, and the locale, then rebuilds cache/pool/prewarm while keeping HTTP executors.
     */
    public void reloadPlugin() {
        PromptCatalog.Parsed parsed = readPrompts();
        if (!parsed.valid()) {
            throw new IllegalStateException(
                    "prompts.yml has a syntax error. The file and the loaded prompts were left unchanged. "
                            + parsed.error());
        }
        migrateConfigs();
        if (!mergeMissingConfig()) {
            throw new IllegalStateException(
                    "config.yml has a syntax error. The file and the loaded configuration were left unchanged.");
        }
        reloadConfig();
        pluginConfig.reload(getConfig());
        applyPrompts(parsed);
        messageService.reload(pluginConfig.getLocale());

        stopRuntimeServices(true);
        startRuntimeServices();
        refreshPlaceholder();
        logCredentialState();
        logMissingEnvVars();

        getLogger().info("NexusAI reloaded (locale=" + pluginConfig.getLocale()
                + ", prompts=" + promptCatalog.ids().size() + ").");
    }

    private void startRuntimeServices() {
        this.aiCache = new AiCache(pluginConfig.getCacheTtl(), pluginConfig.getCacheMaxSize());
        this.rateLimiter = new RateLimiter(
                pluginConfig.getRequestsPerMinute(),
                pluginConfig.getRequestsPerDay(),
                pluginConfig.getPlayerRequestsPerMinute(),
                pluginConfig.getPlayerRequestsPerDay());

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
        this.poolService = new PoolService(
                pluginConfig, aiPool, aiHttpClient, getLogger(), poolStore,
                (delay, task) -> scheduler.schedule(task, Math.max(0L, delay), TimeUnit.MILLISECONDS),
                promptCatalog);
        this.prewarmService = new PrewarmService(
                pluginConfig, aiCache, aiHttpClient, scheduler, getLogger(), promptCatalog);

        poolService.start();
        prewarmService.start();
        prewarmService.scheduleRefresh();
    }

    private void stopRuntimeServices(boolean invalidateCache) {
        if (modelQueue != null) {
            modelQueue.save();
        }
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

    private void refreshPlaceholder() {
        if (placeholderExpansion != null) {
            placeholderExpansion.bind(pluginConfig, aiCache, aiHttpClient, aiPool, poolService, promptCatalog);
            return;
        }
        registerPlaceholderExpansion();
    }

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            if (!loggedMissingPapi) {
                getLogger().warning("PlaceholderAPI not found. Placeholders will be unavailable.");
                loggedMissingPapi = true;
            }
            return;
        }
        loggedMissingPapi = false;
        this.placeholderExpansion = new AiPlaceholderExpansion(
                this, pluginConfig, aiCache, aiHttpClient, aiPool, poolService, promptCatalog);
        if (placeholderExpansion.register()) {
            getLogger().info("Registered PlaceholderAPI expansion "
                    + "%ainexus_generate_<prompt>% / %ainexus_cached_<prompt>%");
        } else {
            getLogger().warning("Failed to register PlaceholderAPI expansion.");
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

    /**
     * @return {@code false} when {@code config.yml} is not valid YAML and was left untouched
     */
    private boolean mergeMissingConfig() {
        saveDefaultConfig();
        File file = new File(getDataFolder(), "config.yml");
        try (InputStream in = getResource("config.yml")) {
            if (in == null || !file.isFile()) {
                return true;
            }
            String defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            ConfigMerger.Result result = ConfigMerger.mergeMissing(existing, defaults);
            if (!result.valid()) {
                return false;
            }
            if (result.addedKeys().isEmpty()) {
                return true;
            }
            Files.writeString(file.toPath(), result.yaml(), StandardCharsets.UTF_8);
            getLogger().info("Added missing config keys: " + String.join(", ", result.addedKeys()));
            reloadConfig();
            return true;
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to merge missing config keys", e);
            return true;
        }
    }

    private PluginConfig heldDefaults() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = getResource("config.yml")) {
            if (in != null) {
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to read the default config from the jar", e);
        }
        PluginConfig config = new PluginConfig(yaml);
        config.holdRequests();
        return config;
    }

    private void logMissingEnvVars() {
        for (String name : pluginConfig.missingEnvVars()) {
            getLogger().warning("Environment variable " + name
                    + " is not set. Its placeholder was replaced with an empty value and is not used as an API key.");
        }
    }

    private void logCredentialState() {
        if (pluginConfig.requestsHeld()) {
            return;
        }
        if (!pluginConfig.canSendRequests()) {
            if (!loggedMissingKey) {
                getLogger().warning("API key is not set (env NEXUSAI_API_KEY or api.key). "
                        + "Plugin will load, but AI requests will not be sent.");
                loggedMissingKey = true;
            }
            return;
        }
        loggedMissingKey = false;
        if (!pluginConfig.hasApiKey()) {
            getLogger().info("No API key set. Requests to this local endpoint omit the Authorization header.");
        }
    }

    private AiProvider createProvider(PluginConfig config) {
        String provider = config.getProvider();
        if (!KNOWN_PROVIDERS.contains(provider)) {
            getLogger().warning("Unknown api.provider '" + provider + "', using OpenAI-compatible client.");
        }
        OpenAiProvider http = new OpenAiProvider(config, httpExecutor, getLogger(), sharedClient(config));
        this.modelQueue = new ModelQueue(
                config.modelQueue(),
                config.modelQueueRemainingThreshold(),
                config.getProviderPauseSeconds() * 1000L,
                config.getAuthPauseSeconds() * 1000L,
                new File(getDataFolder(), "usage.yml"),
                getLogger());
        return new RoutingProvider(config, modelQueue, http, httpExecutor, getLogger());
    }

    private void migrateConfigs() {
        ConfigMigrator.migrateFile(new File(getDataFolder(), "config.yml").toPath(), ConfigMigrator::migrateConfig, getLogger());
        ConfigMigrator.migrateFile(new File(getDataFolder(), "prompts.yml").toPath(), ConfigMigrator::migratePrompts, getLogger());
        ConfigMigrator.migrateFile(new File(getDataFolder(), "pool.yml").toPath(), ConfigMigrator::migratePool, getLogger());
        ConfigMigrator.migrateFile(new File(getDataFolder(), "usage.yml").toPath(), ConfigMigrator::migrateUsage, getLogger());
    }

    private HttpClient sharedClient(PluginConfig config) {
        Duration timeout = config.getConnectTimeout();
        if (sharedHttpClient != null && timeout.equals(sharedHttpClient.connectTimeout().orElse(null))) {
            return sharedHttpClient;
        }
        HttpClient previous = sharedHttpClient;
        sharedHttpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        closeHttpClient(previous);
        return sharedHttpClient;
    }

    private void closeSharedHttpClient() {
        closeHttpClient(sharedHttpClient);
        sharedHttpClient = null;
    }

    private static void closeHttpClient(HttpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.shutdownNow();
        } catch (RuntimeException ignored) {
            // A closed client must not fail reload or shutdown.
        }
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

    public PromptCatalog getPromptCatalog() {
        return promptCatalog;
    }

    public ModelQueue getModelQueue() {
        return modelQueue;
    }

    private void loadPrompts() {
        PromptCatalog.Parsed parsed = readPrompts();
        if (!parsed.valid()) {
            getLogger().warning("prompts.yml has a syntax error (" + parsed.error()
                    + "). Named prompts are disabled until the file is fixed. Literal placeholders still work.");
            this.promptCatalog = PromptCatalog.empty();
            return;
        }
        applyPrompts(parsed);
    }

    private void applyPrompts(PromptCatalog.Parsed parsed) {
        this.promptCatalog = parsed.catalog();
        for (String warning : parsed.warnings()) {
            getLogger().warning(warning);
        }
        List<String> poolPrompts = new ArrayList<>();
        for (PoolEntry entry : pluginConfig.getPoolEntries()) {
            poolPrompts.add(entry.prompt());
        }
        for (String warning : promptCatalog.unknownIdReferences("pool.entries", poolPrompts)) {
            getLogger().warning(warning);
        }
        for (String warning : promptCatalog.unknownIdReferences("prewarm.prompts", pluginConfig.getPrewarmPrompts())) {
            getLogger().warning(warning);
        }
    }

    private PromptCatalog.Parsed readPrompts() {
        File file = new File(getDataFolder(), "prompts.yml");
        if (!file.exists()) {
            saveResource("prompts.yml", false);
        }
        if (!file.isFile()) {
            return PromptCatalog.Parsed.invalid("prompts.yml is missing");
        }
        try {
            return PromptCatalog.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to read prompts.yml", e);
            return PromptCatalog.Parsed.invalid(e.getMessage());
        }
    }
}
