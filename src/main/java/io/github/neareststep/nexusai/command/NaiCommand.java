package io.github.neareststep.nexusai.command;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiErrors;
import io.github.neareststep.nexusai.ai.CompletionSupport;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.FallbackModel;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.context.ContextVariables;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.i18n.MessageService;
import io.github.neareststep.nexusai.knowledge.KnowledgeComposer;
import io.github.neareststep.nexusai.placeholder.VarSubstitutor;
import io.github.neareststep.nexusai.prompt.PromptImporter;
import io.github.neareststep.nexusai.prompt.ResolvedPrompt;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Handles {@code /nai} admin subcommands.
 */
public final class NaiCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "version", "reload", "status", "test", "prompts", "talk");
    private static final String DEFAULT_TEST_PROMPT = "Reply with exactly the word pong.";

    private final NexusAI plugin;

    public NaiCommand(NexusAI plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        MessageService messages = plugin.getMessageService();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("nexusai.command");
        boolean canTalk = sender.hasPermission("nexusai.talk") || admin;
        if ("talk".equals(sub)) {
            if (!canTalk) {
                messages.send(sender, "command.no-permission");
                return true;
            }
            handleTalk(sender, messages, args);
            return true;
        }
        if (!admin) {
            if ("help".equals(sub) && sender.hasPermission("nexusai.talk")) {
                sendTalkHelp(sender, messages);
                return true;
            }
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (args.length > 1 && !"test".equals(sub) && !"prompts".equals(sub)) {
            messages.send(sender, "command.extra-args");
            return true;
        }
        switch (sub) {
            case "help" -> sendHelp(sender, messages);
            case "version" -> messages.send(sender, "command.version", Map.of(
                    "version", plugin.getPluginMeta().getVersion(),
                    "authors", formatAuthors(plugin.getPluginMeta().getAuthors())
            ));
            case "reload" -> handleReload(sender, messages);
            case "status" -> handleStatus(sender, messages);
            case "test" -> handleTest(sender, messages, args);
            case "prompts" -> handlePrompts(sender, messages, args);
            default -> messages.send(sender, "command.unknown");
        }
        return true;
    }

    /**
     * Joins {@code authors} from plugin.yml. Names are not hardcoded here.
     */
    static String formatAuthors(List<String> authors) {
        if (authors == null || authors.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String author : authors) {
            if (author == null || author.isBlank()) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(", ");
            }
            joined.append(author.trim());
        }
        return joined.toString();
    }

    private void sendHelp(CommandSender sender, MessageService messages) {
        messages.send(sender, "command.help-header");
        messages.send(sender, "command.help-help");
        messages.send(sender, "command.help-version");
        if (sender.hasPermission("nexusai.reload")) {
            messages.send(sender, "command.help-reload");
        }
        if (sender.hasPermission("nexusai.status")) {
            messages.send(sender, "command.help-status");
        }
        if (sender.hasPermission("nexusai.test")) {
            messages.send(sender, "command.help-test");
        }
        messages.send(sender, "command.help-prompts");
        if (sender.hasPermission("nexusai.import")) {
            messages.send(sender, "command.help-prompts-import");
        }
        if (sender.hasPermission("nexusai.talk") || sender.hasPermission("nexusai.command")) {
            messages.send(sender, "command.help-talk");
            messages.send(sender, "command.help-talk-end");
        }
    }

    private void sendTalkHelp(CommandSender sender, MessageService messages) {
        messages.send(sender, "command.help-header");
        messages.send(sender, "command.help-talk");
        messages.send(sender, "command.help-talk-end");
    }

    private void handleTalk(CommandSender sender, MessageService messages, String[] args) {
        if (plugin.getDialogueService() == null) {
            messages.send(sender, "talk.disabled");
            return;
        }
        if (args.length < 2) {
            messages.send(sender, "talk.usage");
            return;
        }
        if ("end".equalsIgnoreCase(args[1])) {
            if (args.length > 2) {
                messages.send(sender, "command.extra-args");
                return;
            }
            if (!(sender instanceof Player player)) {
                messages.send(sender, "talk.no-session");
                return;
            }
            plugin.getDialogueService().end(player);
            return;
        }
        Player player;
        String id;
        int messageAt;
        if (sender instanceof Player self) {
            player = self;
            id = args[1];
            messageAt = 2;
        } else {
            if (args.length < 3) {
                messages.send(sender, "talk.players-only");
                return;
            }
            player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                messages.send(sender, "talk.unknown-player", Map.of("player", args[1]));
                return;
            }
            id = args[2];
            messageAt = 3;
        }
        String message = messageAt >= args.length
                ? ""
                : String.join(" ", Arrays.copyOfRange(args, messageAt, args.length)).trim();
        plugin.getDialogueService().fromCommand(player, id, message);
    }

    private void handleReload(CommandSender sender, MessageService messages) {
        if (!sender.hasPermission("nexusai.reload")) {
            messages.send(sender, "command.no-permission");
            return;
        }
        try {
            plugin.reloadPlugin();
            plugin.getMessageService().send(sender, "command.reload-ok");
        } catch (Exception e) {
            messages.send(sender, "command.reload-fail", Map.of(
                    "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ));
            plugin.getLogger().warning("Reload failed: " + e.getMessage());
        }
    }

    private void handleStatus(CommandSender sender, MessageService messages) {
        if (!sender.hasPermission("nexusai.status")) {
            messages.send(sender, "command.no-permission");
            return;
        }
        PluginConfig config = plugin.getPluginConfig();
        String yes = messages.format("common.yes");
        String no = messages.format("common.no");
        String enabled = messages.format("common.enabled");
        String disabled = messages.format("common.disabled");

        messages.send(sender, "command.status-header");
        messages.send(sender, "command.status-locale", Map.of("locale", messages.getLocale()));
        messages.send(sender, "command.status-provider", Map.of("provider", config.getProvider()));
        messages.send(sender, "command.status-base-url", Map.of("base_url", config.getBaseUrl()));
        messages.send(sender, "command.status-model", Map.of("model", config.getModel()));
        String masked = config.maskedApiKeys();
        messages.send(sender, "command.status-api-key", Map.of(
                "api_key", masked.isBlank() ? no : masked
        ));
        messages.send(sender, "command.status-pool", Map.of(
                "pool_state", config.isPoolEnabled() ? enabled : disabled,
                "pool_entries", String.valueOf(config.getPoolEntries().size())
        ));
        java.util.List<String> unpooled = plugin.getUnpooledGenerateLog().prompts();
        if (!unpooled.isEmpty()) {
            messages.send(sender, "command.status-unpooled", Map.of(
                    "prompts", String.join(", ", unpooled)
            ));
        }
        messages.send(sender, "command.status-cache", Map.of(
                "cache_size", String.valueOf(plugin.getAiCache().size())
        ));
        messages.send(sender, "command.status-prompts", Map.of(
                "prompts", String.valueOf(plugin.getPromptCatalog().ids().size())
        ));
        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        messages.send(sender, "command.status-papi", Map.of("papi", papi ? yes : no));
        String lastError = plugin.getAiHttpClient().lastErrorText();
        messages.send(sender, "command.status-last-error", Map.of(
                "last_error", lastError == null || lastError.isBlank()
                        ? messages.raw("common.none")
                        : redact(lastError)
        ));
        messages.send(sender, "command.status-provider-pause", Map.of(
                "provider_pause", pauseText(messages)
        ));
        ModelQueue queue = plugin.getModelQueue();
        if (queue != null && queue.size() > 0) {
            messages.send(sender, "command.status-queue-header");
            for (ModelQueue.Status row : queue.status(System.currentTimeMillis())) {
                messages.send(sender, "command.status-queue-line", Map.of("entry", queueLine(row)));
            }
        }
        FallbackModel fallback = config.fallbackModel();
        String fallbackEntry = messages.raw("common.none");
        if (fallback.configured() && queue != null) {
            fallbackEntry = queueLine(queue.fallbackStatus(fallback.provider(), fallback.model(), System.currentTimeMillis()));
        }
        messages.send(sender, "command.status-fallback-model", Map.of("entry", fallbackEntry));
        messages.send(sender, "command.status-knowledge", Map.of(
                "files", String.valueOf(plugin.getKnowledgeBase().size())
        ));
    }

    static String queueLine(ModelQueue.Status row) {
        String limit = row.dailyLimit() > 0 ? Integer.toString(row.dailyLimit()) : "-";
        StringBuilder line = new StringBuilder();
        line.append(row.provider()).append(" / ").append(row.model())
                .append(": ").append(row.requestsToday()).append('/').append(limit).append(" today");
        if (row.remainingRequests() != null || row.remainingTokens() != null) {
            line.append(", remaining");
            if (row.remainingRequests() != null) {
                line.append(' ').append(row.remainingRequests()).append(" requests");
            }
            if (row.remainingTokens() != null) {
                if (row.remainingRequests() != null) {
                    line.append(" /");
                }
                line.append(' ').append(row.remainingTokens()).append(" tokens");
            }
        }
        line.append(", rejected ").append(row.rejected());
        line.append(", ").append(row.state());
        return line.toString();
    }

    private String pauseText(MessageService messages) {
        if (!plugin.getAiHttpClient().isProviderPaused()) {
            return messages.raw("common.no");
        }
        AiErrorKind kind = plugin.getAiHttpClient().pauseKind();
        String kindText = messages.raw("error." + (kind == null ? "other" : kind.langKey()));
        return kindText + ", " + plugin.getAiHttpClient().pauseRemainingSeconds() + "s";
    }

    private void handleTest(CommandSender sender, MessageService messages, String[] args) {
        if (sender instanceof Player player && !ownsRegion(player)) {
            player.getScheduler().run(plugin, scheduled -> handleTest(sender, plugin.getMessageService(), args), () ->
                    plugin.getLogger().fine("Skipped /nai test because the player is no longer valid"));
            return;
        }
        if (!sender.hasPermission("nexusai.test")) {
            messages.send(sender, "command.no-permission");
            return;
        }
        String prompt = args.length <= 1
                ? DEFAULT_TEST_PROMPT
                : String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (prompt.isEmpty()) {
            messages.send(sender, "command.test-fail", testPlaceholders(0L, "empty prompt"));
            return;
        }
        GenerationOverrides overrides = GenerationOverrides.none();
        if (plugin.getPromptCatalog().find(prompt).isPresent()) {
            Player player = sender instanceof Player online ? online : null;
            ResolvedPrompt resolved = plugin.getPromptCatalog().resolve(
                    prompt,
                    plugin.getPluginConfig(),
                    template -> VarSubstitutor.resolve(player, template),
                    ContextVariables.capture(player));
            if (!resolved.usable()) {
                String detail = resolved.text().isBlank() ? "empty prompt" : "prompt is longer than max-prompt-length";
                messages.send(sender, "command.test-fail", testPlaceholders(0L, detail));
                return;
            }
            prompt = resolved.text();
            overrides = KnowledgeComposer.prepare(
                    resolved.overrides(),
                    plugin.getPluginConfig().getSystemPrompt(),
                    plugin.getKnowledgeBase(),
                    resolved.knowledge()).overrides();
        } else if (args.length > 1) {
            prompt = outgoingTestPrompt(prompt, true);
        }
        messages.send(sender, "command.test-sending");
        long started = System.nanoTime();
        String requestPrompt = prompt;
        GenerationOverrides requestOverrides = overrides;
        CompletionSupport.onComplete(
                plugin.getAiHttpClient().testAsync(requestPrompt, requestOverrides),
                plugin.getLogger(),
                "Failed to deliver /nai test result",
                (answer, error) -> SenderTasks.run(plugin, sender, () -> {
                    long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                    MessageService current = plugin.getMessageService();
                    if (error != null) {
                        String detail = AiErrors.detail(error);
                        if (detail.isBlank()) {
                            detail = error.getClass().getSimpleName();
                        }
                        current.send(sender, "command.test-fail", testPlaceholders(latencyMs, redact(detail)));
                    } else {
                        current.send(sender, "command.test-ok", testPlaceholders(latencyMs, redact(answer == null ? "" : answer)));
                    }
                }, plugin.getLogger()));
    }

    private void handlePrompts(CommandSender sender, MessageService messages, String[] args) {
        if (args.length >= 2 && "import".equals(args[1].toLowerCase(Locale.ROOT))) {
            handleImport(sender, messages, args);
            return;
        }
        if (args.length > 1) {
            messages.send(sender, "command.extra-args");
            return;
        }
        List<String> ids = plugin.getPromptCatalog().ids();
        if (ids.isEmpty()) {
            messages.send(sender, "command.prompts-empty");
            return;
        }
        messages.send(sender, "command.prompts-header", Map.of("count", Integer.toString(ids.size())));
        for (String id : ids) {
            messages.send(sender, "command.prompts-line", Map.of("id", id));
        }
    }

    /**
     * Import writes {@code prompts.yml}. Listing ids stays on {@code nexusai.command}.
     * {@code nexusai.import} is separate so an operator can list prompts without being allowed to rewrite the file.
     */
    private void handleImport(CommandSender sender, MessageService messages, String[] args) {
        if (!sender.hasPermission("nexusai.import")) {
            messages.send(sender, "command.no-permission");
            return;
        }
        if (args.length < 3 || args.length > 4) {
            messages.send(sender, "command.extra-args");
            return;
        }
        boolean overwrite = false;
        if (args.length == 4) {
            if (!"--overwrite".equals(args[3])) {
                messages.send(sender, "command.prompts-import-fail", Map.of("error", "unknown option " + args[3]));
                return;
            }
            overwrite = true;
        }
        PromptImporter.Report report = PromptImporter.importFile(plugin.getDataFolder().toPath(), args[2], overwrite);
        if (!report.success()) {
            messages.send(sender, "command.prompts-import-fail", Map.of(
                    "error", report.error() == null ? "import failed" : report.error()
            ));
            return;
        }
        for (String warning : report.warnings()) {
            plugin.getLogger().warning(warning);
        }
        if (report.changed()) {
            try {
                plugin.reloadPlugin();
            } catch (Exception e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                plugin.getMessageService().send(sender, "command.reload-fail", Map.of("error", error));
                return;
            }
        }
        MessageService current = plugin.getMessageService();
        String none = current.raw("common.none");
        current.send(sender, "command.prompts-import-ok", Map.of(
                "file", report.fileName(),
                "added", report.added().isEmpty() ? none : String.join(", ", report.added()),
                "skipped", report.skipped().isEmpty() ? none : String.join(", ", report.skipped()),
                "conflicting", report.conflicting().isEmpty() ? none : String.join(", ", report.conflicting())
        ));
    }

    /**
     * Free text typed into {@code /nai test} is player input and is sanitized and wrapped.
     * A named prompt id is resolved by the catalog instead, so the admin template is not wrapped
     * as a whole. The built-in default probe stays literal.
     */
    static String outgoingTestPrompt(String raw, boolean wrapPlayerText) {
        if (raw == null) {
            return "";
        }
        if (!wrapPlayerText) {
            return raw;
        }
        return PlayerInput.wrap(raw);
    }

    private static boolean ownsRegion(Player player) {
        try {
            if (Bukkit.getServer() == null) {
                return true;
            }
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private String redact(String text) {
        return io.github.neareststep.nexusai.config.SecretMask.redact(text, plugin.getPluginConfig().configuredSecrets());
    }

    private static Map<String, String> testPlaceholders(long latencyMs, String text) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("latency_ms", Long.toString(latencyMs));
        values.put("error", text);
        values.put("answer", text);
        return values;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        boolean admin = sender.hasPermission("nexusai.command");
        boolean canTalk = admin || sender.hasPermission("nexusai.talk");
        if (!admin && !canTalk) {
            return List.of();
        }
        if (args.length == 2 && "talk".equals(args[0].toLowerCase(Locale.ROOT)) && canTalk) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();
            if ("end".startsWith(prefix)) {
                ids.add("end");
            }
            if (!(sender instanceof Player)) {
                try {
                    if (Bukkit.getServer() != null) {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                ids.add(player.getName());
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Tab completion must not fail when the server is not booted.
                }
            }
            for (String id : plugin.getPromptCatalog().ids()) {
                if (id.startsWith(prefix)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        if (args.length == 3 && "talk".equals(args[0].toLowerCase(Locale.ROOT)) && !(sender instanceof Player)) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();
            for (String id : plugin.getPromptCatalog().ids()) {
                if (id.startsWith(prefix)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        if (args.length >= 2 && "prompts".equals(args[0].toLowerCase(Locale.ROOT))) {
            return completePrompts(sender, args);
        }
        if (args.length == 2 && "test".equals(args[0].toLowerCase(Locale.ROOT)) && sender.hasPermission("nexusai.test")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();
            for (String id : plugin.getPromptCatalog().ids()) {
                if (id.startsWith(prefix)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        if (args.length != 1) {
            return List.of();
        }
        if (!admin) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return "talk".startsWith(prefix) ? List.of("talk") : List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            if (!sub.startsWith(prefix)) {
                continue;
            }
            if ("reload".equals(sub) && !sender.hasPermission("nexusai.reload")) {
                continue;
            }
            if ("status".equals(sub) && !sender.hasPermission("nexusai.status")) {
                continue;
            }
            if ("test".equals(sub) && !sender.hasPermission("nexusai.test")) {
                continue;
            }
            if ("talk".equals(sub) && !sender.hasPermission("nexusai.talk") && !sender.hasPermission("nexusai.command")) {
                continue;
            }
            out.add(sub);
        }
        return out;
    }

    private List<String> completePrompts(CommandSender sender, String[] args) {
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (!sender.hasPermission("nexusai.import")) {
                return List.of();
            }
            return "import".startsWith(action) ? List.of("import") : List.of();
        }
        if (!sender.hasPermission("nexusai.import") || !"import".equals(args[1].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        if (args.length == 3) {
            return importFileNames(args[2]);
        }
        if (args.length == 4 && "--overwrite".startsWith(args[3])) {
            return List.of("--overwrite");
        }
        return List.of();
    }

    private List<String> importFileNames(String prefix) {
        Path folder = plugin.getDataFolder().toPath().resolve("import");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        try (Stream<Path> files = Files.list(folder)) {
            List<String> names = new ArrayList<>();
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if ((lower.endsWith(".yml") || lower.endsWith(".yaml")) && lower.startsWith(needle)) {
                    names.add(name);
                }
            }
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }
}
