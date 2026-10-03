package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.LengthTrimNotices;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.ConfigMigrator;
import io.github.neareststep.nexusai.config.ConfigStartup;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.command.NaiCommand;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.i18n.MessageService;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.moderation.ChatModerationListener;
import io.github.neareststep.nexusai.moderation.FoliaStaffNotifier;
import io.github.neareststep.nexusai.moderation.ModerationLog;
import io.github.neareststep.nexusai.moderation.ModerationService;
import io.github.neareststep.nexusai.placeholder.AiPlaceholderExpansion;
import io.github.neareststep.nexusai.config.ModerationSettings;
import io.github.neareststep.nexusai.config.PoolEntry;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import io.github.neareststep.nexusai.pool.PoolStore;
import io.github.neareststep.nexusai.pool.UnpooledGenerateLog;
import io.github.neareststep.nexusai.prewarm.PrewarmService;
import io.github.neareststep.nexusai.api.NexusAIApi;
import io.github.neareststep.nexusai.context.CachedContextCoordinator;
import io.github.neareststep.nexusai.context.ContextRegistry;
import io.github.neareststep.nexusai.context.ContextService;
import io.github.neareststep.nexusai.context.ContextSnapshots;
import io.github.neareststep.nexusai.dialogue.DialogueListener;
import io.github.neareststep.nexusai.dialogue.DialogueService;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
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
    private HttpPool httpPool;
    private ExecutorService httpExecutor;
    private ScheduledExecutorService scheduler;
    private HttpClient sharedHttpClient;
    private AiPlaceholderExpansion placeholderExpansion;
    private volatile PromptCatalog promptCatalog = PromptCatalog.empty();
    private volatile KnowledgeBase knowledgeBase = KnowledgeBase.empty();
    private volatile ModelQueue modelQueue;
    private DialogueService dialogueService;
    private volatile OpenAiProvider openAiProvider;
    private volatile ModerationService moderationService;
    private ChatModerationListener moderationListener;
    private UnpooledGenerateLog unpooledGenerateLog;
    private String loggedCredentialWarning = "";
    private boolean loggedMissingPapi;
    private ExecutorService contextExecutor;
    private ContextRegistry contextRegistry;
    private ContextSnapshots contextSnapshots;
    private ContextService contextService;
    private CachedContextCoordinator contextCoordinator;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs() && !getDataFolder().isDirectory()) {
            getLogger().warning("Could not create the NexusAI data folder.");
        }
        PluginConfig.secretsBase = getDataFolder().toPath();
        saveDefaultConfig();
        if (!new File(getDataFolder(), "prompts.yml").isFile()) {
            saveResource("prompts.yml", false);
        }
        prepareDataFolders();
        this.unpooledGenerateLog = new UnpooledGenerateLog(getLogger());
        if (!prepareConfigFile()) {
            getLogger().warning("config.yml has a syntax error. The file was left unchanged, "
                    + "and AI requests stay off until a valid /nai reload.");
            this.pluginConfig = heldDefaults();
        } else {
            this.pluginConfig = new PluginConfig(getConfig());
        }
        this.messageService = new MessageService(this);
        this.messageService.reload(pluginConfig.getLocale());
        loadKnowledge();
        this.contextRegistry = new ContextRegistry(getLogger());
        NexusAIApi.bindContextRegistry(contextRegistry);
        getServer().getPluginManager().registerEvents(contextRegistry, this);
        contextRegistry.load(getServer().getServicesManager());
        loadPrompts();
        logCredentialState();
        logGroqMaxTokensWarning();
        logMissingEnvVars();
        logKeyFileWarnings();
        logQueueStrategy();
        logHttpLimitWarning();

        getLogger().info("Using provider: " + pluginConfig.getProvider()
                + ", base-url: " + pluginConfig.getBaseUrl()
                + ", model: " + pluginConfig.getModel());
        String maskedKeys = pluginConfig.maskedApiKeys();
        if (!maskedKeys.isBlank()) {
            getLogger().info("API keys: " + maskedKeys);
        }

        this.httpPool = HttpPool.create(
                getLogger(), pluginConfig.httpMaxInFlight(), pluginConfig.httpQueueSize());
        this.httpExecutor = httpPool.executor();
        this.scheduler = createScheduler();
        this.contextExecutor = ContextService.newWorkerPool(ContextService.THREADS, ContextService.QUEUE_CAPACITY);
        this.contextSnapshots = new ContextSnapshots();
        this.contextService = new ContextService(
                contextRegistry,
                pluginConfig.contextSettings(),
                contextExecutor,
                scheduler,
                getLogger(),
                System::currentTimeMillis,
                pluginConfig.getErrorLogCooldownSeconds());
        this.contextCoordinator = new CachedContextCoordinator(
                contextSnapshots,
                System::currentTimeMillis,
                () -> pluginConfig.contextSettings().refresh());
        startRuntimeServices();
        registerPlaceholderExpansion();
        registerCommands();
        registerModerationListener();

        getLogger().info("NexusAI enabled.");
    }

    @Override
    public void onDisable() {
        unregisterPlaceholderExpansion();
        if (dialogueService != null) {
            dialogueService.shutdown();
            NexusAIApi.bind(null);
            dialogueService = null;
        }
        stopRuntimeServices(true);
        closeSharedHttpClient();
        shutdownExecutor(contextExecutor);
        shutdownExecutor(scheduler);
        shutdownExecutor(httpExecutor);
        NexusAIApi.bindContextRegistry(null);
        getLogger().info("NexusAI disabled.");
    }

    /**
     * Reloads config.yml, prompts.yml, and the locale, then rebuilds cache/pool/prewarm while keeping HTTP executors.
     */
    public void reloadPlugin() {
        PluginConfig.secretsBase = getDataFolder().toPath();
        PromptCatalog.Parsed parsed = readPrompts();
        if (!parsed.valid()) {
            throw new IllegalStateException(
                    "prompts.yml has a syntax error. The file and the loaded prompts were left unchanged. "
                            + parsed.error());
        }
        if (!prepareConfigFile()) {
            throw new IllegalStateException(
                    "config.yml has a syntax error. The file and the loaded configuration were left unchanged.");
        }
        reloadConfig();
        pluginConfig.reload(getConfig());
        loadKnowledge();
        applyPrompts(parsed);
        messageService.reload(pluginConfig.getLocale());
        getUnpooledGenerateLog().reset();
        LengthTrimNotices.reset();

        stopRuntimeServices(true);
        if (httpPool != null) {
            httpPool.applyLimits(pluginConfig.httpMaxInFlight(), pluginConfig.httpQueueSize());
        }
        startRuntimeServices();
        refreshPlaceholder();
        logCredentialState();
        logGroqMaxTokensWarning();
        logMissingEnvVars();
        logKeyFileWarnings();
        logQueueStrategy();
        logHttpLimitWarning();

        getLogger().info("NexusAI reloaded (locale=" + pluginConfig.getLocale()
                + ", prompts=" + promptCatalog.ids().size() + ").");
    }

    private void startRuntimeServices() {
        this.aiCache = new AiCache(pluginConfig.getCacheTtl(), pluginConfig.getCacheMaxSize(), pluginConfig.allowMarkup());
        this.rateLimiter = new RateLimiter(
                pluginConfig.getRequestsPerMinute(),
                pluginConfig.getRequestsPerDay(),
                pluginConfig.getPlayerRequestsPerMinute(),
                pluginConfig.getPlayerRequestsPerDay());

        AiProvider provider = createProvider(pluginConfig);
        RequestGate gate = RequestGate.fromConfig(rateLimiter, pluginConfig);
        // Enable and /nai reload both build this gate. resetBackoff clears per-prompt backoff,
        // including an empty-reply ladder, so a changed prompt is sent again.
        gate.resetBackoff();
        AiDiagnostics diagnostics = new AiDiagnostics(
                getLogger(),
                Duration.ofSeconds(pluginConfig.getErrorLogCooldownSeconds()),
                pluginConfig::configuredSecrets);
        this.aiHttpClient = new AiHttpClient(aiCache, provider, pluginConfig, gate, diagnostics, getLogger());
        this.aiPool = new AiPool(pluginConfig.allowMarkup());
        PoolStore poolStore = new PoolStore(
                new File(getDataFolder(), "pool.yml"),
                scheduler,
                Duration.ofSeconds(pluginConfig.getPoolSaveDelaySeconds()),
                getLogger(),
                pluginConfig.isPoolPersist(),
                pluginConfig.allowMarkup()
        );
        this.poolService = new PoolService(
                pluginConfig, aiPool, aiHttpClient, getLogger(), poolStore,
                (delay, task) -> scheduler.schedule(task, Math.max(0L, delay), TimeUnit.MILLISECONDS),
                promptCatalog, knowledgeBase);
        this.prewarmService = new PrewarmService(
                pluginConfig, aiCache, aiHttpClient, scheduler, getLogger(), promptCatalog, knowledgeBase);

        poolService.start();
        prewarmService.start();
        prewarmService.scheduleRefresh();
        if (dialogueService == null) {
            dialogueService = new DialogueService(this, httpExecutor, scheduler);
            dialogueService.start();
            getServer().getPluginManager().registerEvents(new DialogueListener(this), this);
            NexusAIApi.bind(dialogueService);
        } else {
            dialogueService.resetSummaryStats();
        }
        startModeration();
    }

    private void stopRuntimeServices(boolean invalidateCache) {
        // Context providers stay registered across /nai reload. Only snapshots and health reset.
        if (contextSnapshots != null) {
            contextSnapshots.clear();
        }
        if (contextService != null && pluginConfig != null) {
            contextService.apply(pluginConfig.contextSettings(), pluginConfig.getErrorLogCooldownSeconds());
        }
        this.moderationService = null;
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
     * Migrates {@code config.yml} and appends missing default keys.
     * A single startup writes one backup, even when both steps change the file.
     *
     * @return {@code false} when {@code config.yml} is not valid YAML and was left untouched
     */
    private boolean prepareConfigFile() {
        saveDefaultConfig();
        File file = new File(getDataFolder(), "config.yml");
        try (InputStream in = getResource("config.yml")) {
            String defaults = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file.toPath(), defaults, getLogger());
            if (!outcome.valid()) {
                migrateOtherConfigs();
                return false;
            }
            if (outcome.backup() != null) {
                getLogger().info("Backed up config.yml to " + outcome.backup().toAbsolutePath());
            }
            if (!outcome.addedKeys().isEmpty()) {
                getLogger().info("Added missing config keys: " + String.join(", ", outcome.addedKeys()));
                reloadConfig();
            }
            migrateOtherConfigs();
            return true;
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to merge missing config keys", e);
            migrateOtherConfigs();
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

    private void logKeyFileWarnings() {
        for (String warning : pluginConfig.keyFileWarnings()) {
            getLogger().warning(warning);
        }
        for (String warning : pluginConfig.shortKeyWarnings()) {
            getLogger().warning(warning);
        }
    }

    private void logHttpLimitWarning() {
        String warning = pluginConfig.httpLimitWarning();
        if (warning != null && !warning.isBlank()) {
            getLogger().warning(warning);
        }
    }

    /**
     * One warning per startup and per {@code /nai reload}. Not logged per request.
     */
    private void logGroqMaxTokensWarning() {
        if (pluginConfig.requestsHeld()) {
            return;
        }
        String warning = pluginConfig.groqUnlimitedOutputWarning();
        if (warning != null) {
            getLogger().warning(warning);
        }
    }

    private void logCredentialState() {
        if (pluginConfig.requestsHeld()) {
            return;
        }
        String warning = pluginConfig.credentialWarning();
        if (warning != null) {
            if (!warning.equals(loggedCredentialWarning)) {
                loggedCredentialWarning = warning;
                getLogger().warning(warning);
            }
            return;
        }
        loggedCredentialWarning = "";
        if (!pluginConfig.hasApiKey() && pluginConfig.allowsKeylessRequests()) {
            getLogger().info("No API key set. Requests to this local endpoint omit the Authorization header.");
        }
    }

    private AiProvider createProvider(PluginConfig config) {
        String provider = config.getProvider();
        if (!KNOWN_PROVIDERS.contains(provider)) {
            getLogger().warning("Unknown api.provider '" + provider + "', using OpenAI-compatible client.");
        }
        OpenAiProvider http = new OpenAiProvider(
                config, httpExecutor, getLogger(), sharedClient(config), httpPool.gate());
        this.openAiProvider = http;
        this.modelQueue = new ModelQueue(
                config.modelQueue(),
                config.modelQueueRemainingThreshold(),
                config.getProviderPauseSeconds() * 1000L,
                config.getAuthPauseSeconds() * 1000L,
                new File(getDataFolder(), "usage.yml"),
                getLogger(),
                config.modelQueueStrategy());
        return new RoutingProvider(config, modelQueue, http, httpExecutor, getLogger(), httpPool.gate());
    }

    private void logQueueStrategy() {
        String warning = pluginConfig.modelQueueStrategyWarning();
        if (warning != null && !warning.isBlank()) {
            getLogger().warning(warning);
        }
    }

    private void migrateOtherConfigs() {
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

    /**
     * Bounded worker queue and in-flight HTTP cap. Load tests read {@link HttpPool#snapshot()}.
     */
    public HttpPool getHttpPool() {
        return httpPool;
    }

    public PromptCatalog getPromptCatalog() {
        return promptCatalog;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase == null ? KnowledgeBase.empty() : knowledgeBase;
    }

    public UnpooledGenerateLog getUnpooledGenerateLog() {
        if (unpooledGenerateLog == null) {
            unpooledGenerateLog = new UnpooledGenerateLog(getLogger());
        }
        return unpooledGenerateLog;
    }

    public ModelQueue getModelQueue() {
        return modelQueue;
    }

    public ContextRegistry getContextRegistry() {
        return contextRegistry;
    }

    public ContextSnapshots getContextSnapshots() {
        return contextSnapshots;
    }

    public ContextService getContextService() {
        return contextService;
    }

    public CachedContextCoordinator getContextCoordinator() {
        return contextCoordinator;
    }

    public DialogueService getDialogueService() {
        return dialogueService;
    }

    public ModerationService getModerationService() {
        return moderationService;
    }

    private void startModeration() {
        ModerationSettings settings = pluginConfig.moderation();
        if (settings.enabled() && settings.provider().isEmpty() != settings.model().isEmpty()) {
            getLogger().warning("moderation.provider and moderation.model must both be set or both be empty. "
                    + "Using the model queue.");
        }
        if (settings.enabled() && settings.pinned() && pluginConfig.provider(settings.provider()) == null) {
            getLogger().warning("moderation.provider '" + settings.provider()
                    + "' is not defined. Chat checks will be skipped until it is.");
        }
        if (settings.enabled()) {
            getLogger().info("Chat moderation is enabled. Public chat is delivered immediately; "
                    + "the check runs afterwards and never punishes or runs commands.");
        }
        this.moderationService = new ModerationService(
                settings,
                pluginConfig,
                modelQueue,
                openAiProvider,
                httpExecutor,
                new ModerationLog(new File(getDataFolder(), "moderation.log"), getLogger()),
                new FoliaStaffNotifier(this),
                getLogger()
        );
    }

    private void registerModerationListener() {
        if (moderationListener != null) {
            return;
        }
        this.moderationListener = new ChatModerationListener(this);
        getServer().getPluginManager().registerEvents(moderationListener, this);
    }

    private void loadPrompts() {
        PromptCatalog.Parsed parsed = readPrompts();
        if (!parsed.valid()) {
            getLogger().warning("prompts.yml has a syntax error (" + parsed.error()
                    + "). Named prompts are disabled until the file is fixed. Literal placeholders still work.");
            this.promptCatalog = PromptCatalog.empty();
            LengthTrimNotices.usePromptIds(java.util.List.of());
            return;
        }
        applyPrompts(parsed);
    }

    private void applyPrompts(PromptCatalog.Parsed parsed) {
        this.promptCatalog = parsed.catalog();
        LengthTrimNotices.usePromptIds(promptCatalog.ids());
        for (String warning : parsed.warnings()) {
            getLogger().warning(warning);
        }
        for (String id : promptCatalog.ids()) {
            NamedPrompt prompt = promptCatalog.find(id).orElse(null);
            if (prompt == null) {
                continue;
            }
            for (String name : prompt.knowledge()) {
                if (knowledgeBase.unknown(name)) {
                    getLogger().warning("Prompt '" + id + "' lists unknown knowledge file '" + name + "'.");
                }
            }
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
        if (contextRegistry != null) {
            for (String warning : promptCatalog.unknownContextProviders(contextRegistry.activeIds())) {
                getLogger().warning(warning);
            }
        }
        for (String warning : promptCatalog.sharedContextWarnings("pool.entries", poolPrompts)) {
            getLogger().warning(warning);
        }
        for (String warning : promptCatalog.sharedContextWarnings("prewarm.prompts", pluginConfig.getPrewarmPrompts())) {
            getLogger().warning(warning);
        }
    }

    private void prepareDataFolders() {
        File knowledge = new File(getDataFolder(), "knowledge");
        File imports = new File(getDataFolder(), "import");
        if (!knowledge.isDirectory() && !knowledge.mkdirs()) {
            getLogger().warning("Could not create the knowledge folder.");
        }
        if (!imports.isDirectory() && !imports.mkdirs()) {
            getLogger().warning("Could not create the import folder.");
        }
        try {
            KnowledgeBase.ensureExample(knowledge.toPath());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not create the knowledge example file", e);
        }
    }

    private void loadKnowledge() {
        List<String> warnings = new ArrayList<>();
        try {
            KnowledgeBase.ensureExample(new File(getDataFolder(), "knowledge").toPath());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not create the knowledge example file", e);
        }
        this.knowledgeBase = KnowledgeBase.load(
                new File(getDataFolder(), "knowledge").toPath(),
                pluginConfig.knowledgeMaxChars(),
                pluginConfig.knowledgeMaxFileChars(),
                warnings,
                getLogger());
        for (String warning : warnings) {
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
