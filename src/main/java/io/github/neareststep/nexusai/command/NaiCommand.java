package io.github.neareststep.nexusai.command;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.i18n.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Handles {@code /nai} admin subcommands.
 */
public final class NaiCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("help", "version", "reload", "status");

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
        switch (sub) {
            case "help" -> sendHelp(sender, messages);
            case "version" -> messages.send(sender, "command.version", Map.of(
                    "version", plugin.getPluginMeta().getVersion()
            ));
            case "reload" -> handleReload(sender, messages);
            case "status" -> handleStatus(sender, messages);
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
        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        messages.send(sender, "command.status-papi", Map.of("papi", papi ? yes : no));
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (!sender.hasPermission("nexusai.command") || args.length != 1) {
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
            out.add(sub);
        }
        return out;
    }
}
