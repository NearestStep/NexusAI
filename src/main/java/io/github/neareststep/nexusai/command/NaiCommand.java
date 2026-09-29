package io.github.neareststep.nexusai.command;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiErrors;
import io.github.neareststep.nexusai.ai.CompletionSupport;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.i18n.MessageService;
import io.github.neareststep.nexusai.placeholder.VarSubstitutor;
import io.github.neareststep.nexusai.prompt.ResolvedPrompt;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Handles {@code /nai} admin subcommands.
 */
public final class NaiCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "version", "reload", "status", "test", "prompts");
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
        if (!sender.hasPermission("nexusai.command")) {
            messages.send(sender, "command.no-permission");
            return true;
        }

        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1 && !"test".equals(sub)) {
            messages.send(sender, "command.extra-args");
            return true;
        }
        switch (sub) {
            case "help" -> sendHelp(sender, messages);
            case "version" -> messages.send(sender, "command.version", Map.of(
                    "version", plugin.getPluginMeta().getVersion()
            ));
            case "reload" -> handleReload(sender, messages);
            case "status" -> handleStatus(sender, messages);
            case "test" -> handleTest(sender, messages, args);
            case "prompts" -> handlePrompts(sender, messages);
            default -> messages.send(sender, "command.unknown");
        }
        return true;
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
        messages.send(sender, "command.status-api-key", Map.of(
                "api_key", config.hasApiKey() ? yes : no
        ));
        messages.send(sender, "command.status-pool", Map.of(
                "pool_state", config.isPoolEnabled() ? enabled : disabled,
                "pool_entries", String.valueOf(config.getPoolEntries().size())
        ));
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
                "last_error", lastError == null || lastError.isBlank() ? messages.raw("common.none") : lastError
        ));
        messages.send(sender, "command.status-provider-pause", Map.of(
                "provider_pause", pauseText(messages)
        ));
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
                    template -> VarSubstitutor.resolve(player, template));
            if (!resolved.usable()) {
                String detail = resolved.text().isBlank() ? "empty prompt" : "prompt is longer than max-prompt-length";
                messages.send(sender, "command.test-fail", testPlaceholders(0L, detail));
                return;
            }
            prompt = resolved.text();
            overrides = resolved.overrides();
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
                        current.send(sender, "command.test-fail", testPlaceholders(latencyMs, detail));
                    } else {
                        current.send(sender, "command.test-ok", testPlaceholders(latencyMs, answer == null ? "" : answer));
                    }
                }, plugin.getLogger()));
    }

    private void handlePrompts(CommandSender sender, MessageService messages) {
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
        if (!sender.hasPermission("nexusai.command")) {
            return List.of();
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
            out.add(sub);
        }
        return out;
    }
}
