package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.NexusContextProvider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Snapshot of {@code ServicesManager} registrations for {@link NexusContextProvider}.
 * This is not a second registry: plugins register only with Bukkit, and this class reads that list.
 * {@code /nai reload} does not clear it. {@code ServicePriority} is ignored.
 */
public final class ContextRegistry implements Listener {

    public record Entry(String pluginName, NexusContextProvider provider) {
    }

    private final Logger logger;
    private final List<Entry> registrations = new CopyOnWriteArrayList<>();
    private final Set<String> duplicateWarnings = ConcurrentHashMap.newKeySet();
    private final Set<String> invalidWarnings = ConcurrentHashMap.newKeySet();

    public ContextRegistry(Logger logger) {
        this.logger = logger;
    }

    public void load(ServicesManager manager) {
        if (manager == null) {
            return;
        }
        for (RegisteredServiceProvider<NexusContextProvider> registration : manager.getRegistrations(NexusContextProvider.class)) {
            if (registration == null || registration.getProvider() == null) {
                continue;
            }
            String name = registration.getPlugin() == null ? "" : registration.getPlugin().getName();
            add(name, registration.getProvider());
        }
    }

    /**
     * Appends one registration. The same provider instance is ignored. Tests call this directly.
     */
    public void add(String pluginName, NexusContextProvider provider) {
        if (provider == null) {
            return;
        }
        String name = pluginName == null ? "" : pluginName;
        for (Entry existing : registrations) {
            if (existing.provider() == provider) {
                return;
            }
        }
        Entry entry = new Entry(name, provider);
        registrations.add(entry);
        warn(entry);
    }

    public void remove(NexusContextProvider provider) {
        if (provider == null) {
            return;
        }
        registrations.removeIf(entry -> entry.provider() == provider);
    }

    public void remove(String pluginName, String id) {
        String name = pluginName == null ? "" : pluginName;
        registrations.removeIf(entry -> name.equals(entry.pluginName())
                && entry.provider() != null
                && id != null
                && id.equals(safeId(entry.provider())));
    }

    /**
     * Accepted providers: valid id, first registration wins, ordered by {@code priority()} then id.
     */
    public List<Entry> active() {
        Map<String, Entry> first = new LinkedHashMap<>();
        for (Entry entry : registrations) {
            String id = safeId(entry.provider());
            if (!NexusContextProvider.validId(id)) {
                continue;
            }
            first.putIfAbsent(id, entry);
        }
        List<Entry> ordered = new ArrayList<>(first.values());
        ordered.sort(Comparator
                .comparingInt((Entry entry) -> safePriority(entry.provider()))
                .thenComparing(entry -> safeId(entry.provider())));
        return List.copyOf(ordered);
    }

    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (Entry entry : active()) {
            ids.add(safeId(entry.provider()));
        }
        return List.copyOf(ids);
    }

    public Set<String> activeIds() {
        return Set.copyOf(ids());
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (!ours(event.getProvider())) {
            return;
        }
        RegisteredServiceProvider<?> registration = event.getProvider();
        if (registration.getProvider() instanceof NexusContextProvider provider) {
            String name = registration.getPlugin() == null ? "" : registration.getPlugin().getName();
            add(name, provider);
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (!ours(event.getProvider())) {
            return;
        }
        if (event.getProvider().getProvider() instanceof NexusContextProvider provider) {
            remove(provider);
        }
    }

    private void warn(Entry entry) {
        String id = safeId(entry.provider());
        String shown = id == null ? "" : id;
        if (!NexusContextProvider.validId(id)) {
            String key = entry.pluginName() + "\0" + shown;
            if (invalidWarnings.add(key) && logger != null) {
                logger.warning("Ignoring context provider '" + shown + "' from " + plugin(entry)
                        + ": ids must match [a-z0-9_]{1,32}.");
            }
            return;
        }
        for (Entry existing : registrations) {
            if (existing == entry || existing.provider() == entry.provider()) {
                continue;
            }
            String otherId = safeId(existing.provider());
            if (!id.equals(otherId) || !NexusContextProvider.validId(otherId)) {
                continue;
            }
            String key = id + "\0" + existing.pluginName() + "\0" + entry.pluginName();
            if (duplicateWarnings.add(key) && logger != null) {
                logger.warning("Context provider id '" + id + "' is already registered by "
                        + plugin(existing) + ". Ignoring the one from " + plugin(entry) + ".");
            }
            return;
        }
    }

    private static boolean ours(RegisteredServiceProvider<?> registration) {
        return registration != null && registration.getService() == NexusContextProvider.class;
    }

    private static String plugin(Entry entry) {
        return entry.pluginName() == null || entry.pluginName().isBlank() ? "unknown" : entry.pluginName();
    }

    static String safeId(NexusContextProvider provider) {
        if (provider == null) {
            return "";
        }
        try {
            String id = provider.id();
            return id == null ? "" : id;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    static int safePriority(NexusContextProvider provider) {
        if (provider == null) {
            return 100;
        }
        try {
            return provider.priority();
        } catch (RuntimeException ex) {
            return 100;
        }
    }
}
