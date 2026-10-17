package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.api.NexusAIApi;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Startup console banner. Each line is one {@link ConsoleCommandSender#sendMessage}
 * with no {@code [NexusAI]} prefix (Paper's root logger only adds {@code [HH:MM:SS INFO]: }).
 */
public final class ConsoleBanner {

    /** Brand title colour from mockup v11 ({@code #00C5C2}). */
    public static final TextColor BRAND = TextColor.color(0x00C5C2);

    /**
     * Max visible columns for banner text. The console prefix
     * {@code [HH:MM:SS INFO]: } is 17 characters; together they must stay at or under 70.
     */
    public static final int MAX_CONTENT_COLS = 53;

    public static final String GITHUB = "github.com/NearestStep/NexusAI";

    private static final String[] TITLE = {
            "█▄  █ █▀▀▀ ▀▄ ▄▀ █  █ █▀▀▀ ▄▀▀▄ ▀█▀",
            "█ ▀▄█ █▀▀   ▄▀▄  █  █ ▀▀▀█ █▀▀█  █",
            "▀   ▀ ▀▀▀▀ ▀   ▀ ▀▀▀▀ ▀▀▀▀ ▀  ▀ ▀▀▀",
    };

    private static final String SEPARATOR = "─".repeat(MAX_CONTENT_COLS);

    private ConsoleBanner() {
    }

    /**
     * Runtime values shown in the summary. Built once at the end of {@code onEnable}.
     */
    public record Snapshot(
            String version,
            String paperVersion,
            int javaFeature,
            List<String> providers,
            int modelQueueSize,
            int apiVersion,
            boolean quotasEnabled,
            KnowledgeSelect knowledgeSelect,
            long readyMs
    ) {
        public Snapshot {
            version = version == null || version.isBlank() ? "?" : version.trim();
            paperVersion = paperVersion == null || paperVersion.isBlank() ? "?" : paperVersion.trim();
            providers = List.copyOf(providers == null ? List.of() : providers);
            knowledgeSelect = knowledgeSelect == null ? KnowledgeSelect.FULL : knowledgeSelect;
            readyMs = Math.max(0L, readyMs);
        }

        public static Snapshot from(Plugin plugin, PluginConfig config, long readyMs) {
            Objects.requireNonNull(plugin, "plugin");
            Objects.requireNonNull(config, "config");
            List<String> providers = distinctQueueProviders(config);
            if (providers.isEmpty()) {
                String active = config.getProvider();
                if (active != null && !active.isBlank()) {
                    providers = List.of(active.trim().toLowerCase(Locale.ROOT));
                }
            }
            return new Snapshot(
                    plugin.getPluginMeta().getVersion(),
                    Bukkit.getMinecraftVersion(),
                    Runtime.version().feature(),
                    providers,
                    config.modelQueue().size(),
                    NexusAIApi.API_VERSION,
                    config.quotaSettings().enabled(),
                    config.knowledgeSelect(),
                    readyMs);
        }

        private static List<String> distinctQueueProviders(PluginConfig config) {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (QueueEntryConfig entry : config.modelQueue()) {
                if (entry != null && entry.provider() != null && !entry.provider().isBlank()) {
                    ids.add(entry.provider().trim().toLowerCase(Locale.ROOT));
                }
            }
            return List.copyOf(ids);
        }
    }

    /** One summary field: gray label, white value. Empty label means value only. */
    record Field(String label, String value) {
        Field {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
    }

    /**
     * Prints what {@code startup-banner} asks for: the full banner on the console when
     * {@code banner} is true, otherwise the one {@link #readyLine} through the plugin logger.
     */
    public static void announce(boolean banner, Snapshot snapshot, Logger logger) {
        announce(banner, snapshot, line -> Bukkit.getConsoleSender().sendMessage(line), logger);
    }

    static void announce(boolean banner, Snapshot snapshot, Consumer<Component> console, Logger logger) {
        if (snapshot == null) {
            return;
        }
        if (banner) {
            components(snapshot).forEach(console);
        } else if (logger != null) {
            logger.info(readyLine(snapshot.readyMs()));
        }
    }

    /**
     * The short line printed instead of the banner when {@code startup-banner} is false.
     * It carries both markers the smoke and load scripts look for.
     */
    public static String readyLine(long readyMs) {
        return "NexusAI enabled. Ready in " + Math.max(0L, readyMs) + " ms";
    }

    public static void print(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (Component line : components(snapshot)) {
            console.sendMessage(line);
        }
    }

    /**
     * One Adventure component per console record, including the leading empty spacer.
     */
    public static List<Component> components(Snapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        List<Component> out = new ArrayList<>();
        out.add(Component.empty());
        for (String line : TITLE) {
            out.add(Component.text(line, BRAND));
        }
        out.add(Component.text(SEPARATOR, NamedTextColor.DARK_GRAY));
        out.addAll(fullSummary(snapshot));
        return List.copyOf(out);
    }

    /** Plain banner body lines for tests (no spacer). */
    public static List<String> plainBody(Snapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(TITLE));
        lines.add(SEPARATOR);
        for (Component line : fullSummary(snapshot)) {
            lines.add(plain(line));
        }
        return List.copyOf(lines);
    }

    private static List<Component> fullSummary(Snapshot snapshot) {
        String providerNames = joinFitting(
                snapshot.providers(),
                providerNameBudget(snapshot.modelQueueSize()));
        return List.of(
                summaryLine(
                        new Field("Version", snapshot.version()),
                        new Field("Paper", snapshot.paperVersion()),
                        new Field("Java", String.valueOf(snapshot.javaFeature()))),
                summaryLine(
                        new Field("Providers", providerNames),
                        new Field("Model queue", String.valueOf(Math.max(0, snapshot.modelQueueSize())))),
                summaryLine(
                        new Field("API", "v" + snapshot.apiVersion() + " + events"),
                        new Field("Quotas", snapshot.quotasEnabled() ? "on" : "off"),
                        new Field("Knowledge", knowledgeLabel(snapshot.knowledgeSelect()))),
                summaryLine(
                        new Field("Ready in", snapshot.readyMs() + " ms"),
                        new Field("", GITHUB)));
    }

    static Component summaryLine(Field... fields) {
        Field[] fitted = fitFields(fields);
        TextComponent.Builder builder = Component.text();
        for (int i = 0; i < fitted.length; i++) {
            if (i > 0) {
                builder.append(Component.text("  |  ", NamedTextColor.DARK_GRAY));
            }
            Field field = fitted[i];
            if (!field.label().isEmpty()) {
                builder.append(Component.text(field.label() + " ", NamedTextColor.GRAY));
            }
            builder.append(Component.text(field.value(), NamedTextColor.WHITE));
        }
        return builder.build();
    }

    private static Field[] fitFields(Field... fields) {
        String plain = plainFields(fields);
        if (plain.length() <= MAX_CONTENT_COLS) {
            return fields;
        }
        int overflow = plain.length() - MAX_CONTENT_COLS;
        Field[] copy = fields.clone();
        for (int i = copy.length - 1; i >= 0 && overflow > 0; i--) {
            String value = copy[i].value();
            if (value.length() <= overflow) {
                overflow -= value.length();
                copy[i] = new Field(copy[i].label(), "");
                continue;
            }
            copy[i] = new Field(copy[i].label(), ellipsis(value, value.length() - overflow));
            overflow = 0;
        }
        return copy;
    }

    private static String plainFields(Field... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append("  |  ");
            }
            Field field = fields[i];
            if (!field.label().isEmpty()) {
                sb.append(field.label()).append(' ');
            }
            sb.append(field.value());
        }
        return sb.toString();
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText()
                .serialize(component);
    }

    private static String knowledgeLabel(KnowledgeSelect select) {
        return select == KnowledgeSelect.KEYWORDS ? "keywords" : "full";
    }

    static String providersLine(List<String> providers, int modelQueueSize) {
        return plain(summaryLine(
                new Field("Providers", joinFitting(providers, providerNameBudget(modelQueueSize))),
                new Field("Model queue", String.valueOf(Math.max(0, modelQueueSize)))));
    }

    private static int providerNameBudget(int modelQueueSize) {
        String suffix = "  |  Model queue " + Math.max(0, modelQueueSize);
        String head = "Providers ";
        return Math.max(0, MAX_CONTENT_COLS - head.length() - suffix.length());
    }

    private static String joinFitting(List<String> providers, int budget) {
        if (budget < 4) {
            return "…";
        }
        if (providers == null || providers.isEmpty()) {
            return "none";
        }
        StringBuilder joined = new StringBuilder();
        for (String id : providers) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String next = joined.isEmpty() ? id : ", " + id;
            if (joined.length() + next.length() <= budget) {
                joined.append(next);
                continue;
            }
            if (joined.isEmpty()) {
                return ellipsis(id, budget);
            }
            String withEllipsis = joined + ", …";
            if (withEllipsis.length() <= budget) {
                return withEllipsis;
            }
            return ellipsis(joined.toString(), budget);
        }
        return joined.isEmpty() ? "none" : joined.toString();
    }

    private static String ellipsis(String value, int budget) {
        if (budget <= 1) {
            return "…";
        }
        if (value.length() <= budget) {
            return value;
        }
        return value.substring(0, budget - 1) + "…";
    }
}
