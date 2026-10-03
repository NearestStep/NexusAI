package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.context.ContextRegistry;
import io.github.neareststep.nexusai.dialogue.DialogueService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * API for NPC plugins. A blank {@code message} opens a session and completes with the greeting.
 * Any other message is one reply and does not capture later chat.
 * <p>
 * Do not join the returned future on a server region thread. An action command is scheduled back
 * onto that thread, so joining there can stall the call until the action times out.
 * Placeholders never call this API, and this API is the only path that can run character actions.
 * <p>
 * Context providers share Bukkit's {@link ServicesManager}. {@link #registerContextProvider} is a
 * wrapper over that registry. There is no second registry. {@code API_VERSION} 1 was 1.0.x.
 */
public final class NexusAIApi {

    public static final int API_VERSION = 2;

    private static volatile DialogueService service;
    private static volatile ContextRegistry contexts;

    private NexusAIApi() {
    }

    public static void bind(DialogueService dialogueService) {
        service = dialogueService;
    }

    /** Installed by NexusAI. Not part of the provider contract. */
    public static void bindContextRegistry(ContextRegistry registry) {
        contexts = registry;
    }

    /**
     * Registers {@code provider} with {@code ServicesManager} at {@link ServicePriority#Normal}.
     * Priority does not change collection order. Order is {@link NexusContextProvider#priority()} then id.
     */
    public static void registerContextProvider(Plugin owner, NexusContextProvider provider) {
        if (owner == null || provider == null) {
            throw new IllegalArgumentException("owner and provider are required");
        }
        Bukkit.getServicesManager().register(NexusContextProvider.class, provider, owner, ServicePriority.Normal);
    }

    /**
     * Removes this owner's provider with {@code id}. Disabling the owner plugin also removes it,
     * because Bukkit unregisters that plugin's services.
     */
    public static void unregisterContextProvider(Plugin owner, String id) {
        if (owner == null || id == null) {
            return;
        }
        ServicesManager manager = Bukkit.getServicesManager();
        List<NexusContextProvider> drop = new ArrayList<>();
        for (RegisteredServiceProvider<NexusContextProvider> registration : manager.getRegistrations(NexusContextProvider.class)) {
            if (registration == null || registration.getProvider() == null) {
                continue;
            }
            if (owner.equals(registration.getPlugin()) && id.equals(registration.getProvider().id())) {
                drop.add(registration.getProvider());
            }
        }
        for (NexusContextProvider provider : drop) {
            manager.unregister(NexusContextProvider.class, provider);
        }
    }

    /** Ids currently accepted by NexusAI, in collection order. */
    public static List<String> contextProviderIds() {
        ContextRegistry registry = contexts;
        return registry == null ? List.of() : registry.ids();
    }

    public static CompletableFuture<String> talk(Player player, String id, String message) {
        DialogueService current = service;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI is not enabled"));
        }
        if (player == null || id == null || id.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("player and id are required"));
        }
        return current.talk(player, id, message);
    }
}
