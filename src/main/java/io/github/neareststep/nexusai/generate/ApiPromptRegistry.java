package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.api.PromptDefinition;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.dialogue.DialogueProfile;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import io.github.neareststep.nexusai.prompt.PromptContext;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Prompts registered from plugin code. The map survives {@code /nai reload}.
 * {@link PluginDisableEvent} for the owner plugin removes that plugin's prompts.
 * Nothing here is written to a file.
 * <p>
 * Lookup prefers {@code prompts.yml}. A file entry with the same id replaces the code
 * prompt, except {@link PromptDefinition#schema()}, which stays the code value.
 */
public final class ApiPromptRegistry {

    public static final Pattern LOCAL_ID = Pattern.compile("[a-z0-9_-]{1,64}");

    private static final ApiPromptRegistry INSTANCE = new ApiPromptRegistry();

    private final ConcurrentHashMap<String, Entry> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Plugin> namespaceOwners = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    private ApiPromptRegistry() {
    }

    public static ApiPromptRegistry get() {
        return INSTANCE;
    }

    /**
     * Plugin name in lower case. Characters outside {@code [a-z0-9_-]} become {@code _}.
     */
    public static String namespace(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new IllegalArgumentException("plugin name is required");
        }
        String lower = pluginName.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                normalized.append(c);
            } else {
                normalized.append('_');
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("plugin name does not produce a prompt namespace");
        }
        return normalized.toString();
    }

    /**
     * Registers {@code namespace:localId}. The same owner may register the id again to replace it.
     * A different plugin that normalizes to the same namespace throws {@link IllegalStateException}
     * naming both plugins.
     */
    public void register(Plugin owner, String localId, PromptDefinition definition) {
        if (owner == null || definition == null) {
            throw new IllegalArgumentException("owner and definition are required");
        }
        String local = requireLocalId(localId);
        String namespace = namespace(owner.getName());
        String fullId = namespace + ":" + local;
        NamedPrompt prompt = materialize(fullId, definition);
        rejectAdminOnlyFields(prompt);
        synchronized (lock) {
            Plugin current = namespaceOwners.get(namespace);
            if (current != null && !sameOwner(current, owner)) {
                throw new IllegalStateException(
                        "Prompt namespace '" + namespace + "' is already used by plugin '"
                                + nameOf(current) + "'; '" + nameOf(owner) + "' cannot register it");
            }
            namespaceOwners.put(namespace, owner);
            byId.put(fullId, new Entry(owner, namespace, local, fullId, definition, prompt));
        }
    }

    /**
     * Removes this owner's prompt. {@code false} when the id is missing, invalid, or owned
     * by another plugin.
     */
    public boolean unregister(Plugin owner, String localId) {
        if (owner == null || localId == null || !LOCAL_ID.matcher(localId).matches()) {
            return false;
        }
        String namespace;
        try {
            namespace = namespace(owner.getName());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        String fullId = namespace + ":" + localId;
        synchronized (lock) {
            Entry existing = byId.get(fullId);
            if (existing == null || !sameOwner(existing.owner(), owner)) {
                return false;
            }
            byId.remove(fullId);
            releaseNamespaceIfUnused(namespace, owner);
            return true;
        }
    }

    /** Full ids ({@code quests:intro}) for this owner, sorted. */
    public List<String> ids(Plugin owner) {
        if (owner == null) {
            return List.of();
        }
        synchronized (lock) {
            List<String> ids = new ArrayList<>();
            for (Entry entry : byId.values()) {
                if (sameOwner(entry.owner(), owner)) {
                    ids.add(entry.fullId());
                }
            }
            ids.sort(String::compareTo);
            return List.copyOf(ids);
        }
    }

    /**
     * One {@code /nai prompts} line: {@code quests:intro (Quests), shop:price (Shop)}.
     * Empty when nothing is registered.
     */
    public String summary() {
        synchronized (lock) {
            List<Entry> entries = new ArrayList<>(byId.values());
            entries.sort(Comparator.comparing(Entry::fullId));
            StringBuilder line = new StringBuilder();
            for (Entry entry : entries) {
                if (!line.isEmpty()) {
                    line.append(", ");
                }
                line.append(entry.fullId()).append(" (").append(nameOf(entry.owner())).append(')');
            }
            return line.toString();
        }
    }

    /** Snapshot of code prompts. A file id with the same key replaces these at lookup. */
    public Map<String, NamedPrompt> namedPrompts() {
        synchronized (lock) {
            Map<String, NamedPrompt> copy = new LinkedHashMap<>();
            for (Entry entry : byId.values()) {
                copy.put(entry.fullId(), entry.prompt());
            }
            return copy;
        }
    }

    /**
     * File prompt when {@code prompts.yml} defines {@code id}, otherwise the code prompt.
     * The schema is always the code schema when a code prompt exists.
     */
    public Optional<Effective> effective(PromptCatalog catalog, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Entry code;
        synchronized (lock) {
            code = byId.get(id);
        }
        NamedPrompt file = catalog == null ? null : catalog.find(id).orElse(null);
        if (file == null && code == null) {
            return Optional.empty();
        }
        if (file != null) {
            Object schema = code == null ? null : code.definition().schema();
            return Optional.of(new Effective(file, schema, code != null));
        }
        return Optional.of(new Effective(code.prompt(), code.definition().schema(), false));
    }

    /** Drops every prompt owned by {@code plugin}. */
    public void removeOwner(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        synchronized (lock) {
            byId.entrySet().removeIf(entry -> sameOwner(entry.getValue().owner(), plugin));
            namespaceOwners.entrySet().removeIf(entry -> sameOwner(entry.getValue(), plugin));
        }
    }

    /**
     * Drops prompts whose owner plugin is not enabled. Called when NexusAI enables,
     * so a plugin that disabled while NexusAI was off does not keep a stale prompt.
     */
    public void retainEnabled() {
        synchronized (lock) {
            byId.entrySet().removeIf(entry -> !enabled(entry.getValue().owner()));
            namespaceOwners.entrySet().removeIf(entry -> !enabled(entry.getValue()));
        }
    }

    /** Test isolation. {@code /nai reload} does not call this. */
    public void clear() {
        synchronized (lock) {
            byId.clear();
            namespaceOwners.clear();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return byId.isEmpty();
        }
    }

    /**
     * Code prompts cannot carry actions, dialogue, or context. {@link #register} builds a
     * prompt without those fields and then checks it. A prompt that already has one of them
     * is rejected.
     */
    static void rejectAdminOnlyFields(NamedPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        List<String> forbidden = new ArrayList<>();
        if (!prompt.actions().isEmpty()) {
            forbidden.add("actions");
        }
        if (prompt.dialogue().defined()) {
            forbidden.add("dialogue");
        }
        if (prompt.context().active()) {
            forbidden.add("context");
        }
        if (!forbidden.isEmpty()) {
            throw new IllegalArgumentException(
                    "prompts registered from code cannot set " + String.join(", ", forbidden)
                            + "; add them in prompts.yml");
        }
    }

    static NamedPrompt materialize(String fullId, PromptDefinition definition) {
        GenerationOverrides overrides = GenerationOverrides.of(
                definition.systemPrompt() != null,
                definition.systemPrompt(),
                definition.temperature() != null,
                definition.temperature(),
                definition.maxTokens() != null,
                definition.maxTokens(),
                definition.model() != null,
                definition.model());
        return new NamedPrompt(
                fullId,
                definition.text(),
                definition.vars(),
                definition.ttl(),
                definition.fallback(),
                null,
                overrides,
                definition.format(),
                definition.knowledge(),
                null,
                DialogueProfile.absent(),
                List.of(),
                PromptContext.none());
    }

    private void releaseNamespaceIfUnused(String namespace, Plugin owner) {
        for (Entry entry : byId.values()) {
            if (namespace.equals(entry.namespace()) && sameOwner(entry.owner(), owner)) {
                return;
            }
        }
        Plugin held = namespaceOwners.get(namespace);
        if (held != null && sameOwner(held, owner)) {
            namespaceOwners.remove(namespace, held);
        }
    }

    private static String requireLocalId(String localId) {
        if (localId == null || !LOCAL_ID.matcher(localId).matches()) {
            throw new IllegalArgumentException("localId must match [a-z0-9_-]{1,64}");
        }
        return localId;
    }

    private static boolean sameOwner(Plugin left, Plugin right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        String leftName = left.getName();
        String rightName = right.getName();
        return leftName != null && leftName.equals(rightName);
    }

    private static boolean enabled(Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        try {
            return plugin.isEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String nameOf(Plugin plugin) {
        if (plugin == null || plugin.getName() == null) {
            return "";
        }
        return plugin.getName();
    }

    /**
     * Resolved prompt. {@code fileOverride} is true when {@code prompts.yml} replaced the code prompt.
     * {@code schema} is the code schema, or {@code null} when no code prompt is registered for the id.
     */
    public record Effective(NamedPrompt prompt, Object schema, boolean fileOverride) {
    }

    private record Entry(
            Plugin owner,
            String namespace,
            String localId,
            String fullId,
            PromptDefinition definition,
            NamedPrompt prompt
    ) {
    }

    /** Removes an owner plugin's prompts when that plugin disables. */
    public static final class DisableListener implements Listener {

        @EventHandler
        public void onPluginDisable(PluginDisableEvent event) {
            if (event == null) {
                return;
            }
            forget(event.getPlugin());
        }

        /** Drops prompts for the plugin that just disabled. */
        void forget(Plugin plugin) {
            if (plugin == null) {
                return;
            }
            get().removeOwner(plugin);
        }
    }
}
