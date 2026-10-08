package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.context.ContextRegistry;
import io.github.neareststep.nexusai.dialogue.DialogueService;
import io.github.neareststep.nexusai.generate.ApiPromptRegistry;
import io.github.neareststep.nexusai.generate.GenerationService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * API for other plugins. {@link #talk} is the NPC path. {@link #generate} is one asynchronous
 * completion with metadata. Context providers share Bukkit's {@link ServicesManager}.
 * {@link #registerContextProvider} is a wrapper over that registry. There is no second registry.
 * {@code API_VERSION} 1 was 1.0.x. {@code API_VERSION} 2 was 1.1.x.
 * <p>
 * Do not join a returned future on a server region thread. An action command from {@code talk}
 * is scheduled back onto that thread, so joining there can stall the call until the action times
 * out. {@code generate} completes on a NexusAI thread; a callback that edits the world has to
 * hop to the player scheduler or the global region scheduler.
 */
public final class NexusAIApi {

    public static final int API_VERSION = 3;

    private static volatile DialogueService service;
    private static volatile ContextRegistry contexts;
    private static volatile GenerationService generation;

    private NexusAIApi() {
    }

    /** Installed by NexusAI. Not part of the plugin contract. */
    @ApiStatus.Internal
    public static void bind(DialogueService dialogueService) {
        service = dialogueService;
    }

    /** Installed by NexusAI. Not part of the provider contract. */
    @ApiStatus.Internal
    public static void bindContextRegistry(ContextRegistry registry) {
        contexts = registry;
    }

    /** Installed by NexusAI. Not part of the plugin contract. */
    @ApiStatus.Internal
    public static void bindGeneration(GenerationService generationService) {
        generation = generationService;
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

    /**
     * True when NexusAI is enabled, requests are not held after a config error, and
     * {@code plugin-api.enabled} is true.
     * <p>
     * Call from any thread. This does not block and does not check that an API key is set.
     * A true result can still make {@link #generate} finish with {@link NexusErrorKind#NOT_CONFIGURED}
     * when no provider can send. On Folia this method does not read region state.
     */
    public static boolean isAvailable() {
        GenerationService current = generation;
        return current != null && current.available();
    }

    /**
     * One asynchronous generation. Call from any thread, including the main thread and a Folia
     * region thread. The call itself only checks the arguments and schedules work.
     * <p>
     * The future completes on a NexusAI thread ({@code nexusai-http-*} or, if that pool is not
     * accepting work, {@code nexusai-scheduler}) and always completes with a
     * {@link GenerationResult}. On failure {@code success()} is false and {@code text()} is the
     * fallback. The future completes exceptionally only when NexusAI is not enabled, with
     * {@link IllegalStateException} {@code "NexusAI is not enabled"}, the same way {@link #talk}
     * does. That one failure may complete on the calling thread.
     * <p>
     * Do not join the future on the main thread or a region thread. To edit the world, hop back:
     * <pre>{@code
     * NexusAIApi.generate(plugin, GenerationRequest.prompt("quests:intro")
     *                 .player(player)
     *                 .var("quest", questName)
     *                 .label("quest-intro")
     *                 .build())
     *         .thenAccept(result -> player.getScheduler().run(plugin, task -> {
     *             player.sendMessage(result.text());
     *             if (!result.success()) {
     *                 plugin.getLogger().fine("AI failed: " + result.error().map(GenerationError::kind).orElse(null));
     *             }
     *         }, null));
     * }</pre>
     */
    public static CompletableFuture<GenerationResult> generate(Plugin owner, GenerationRequest request) {
        if (owner == null || request == null) {
            throw new IllegalArgumentException("owner and request are required");
        }
        GenerationService current = generation;
        if (current == null || !current.accepting()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI is not enabled"));
        }
        return current.generate(owner, request);
    }

    /**
     * Registers {@code namespace:localId} for {@code owner}. The namespace is {@code owner}'s name
     * in lower case, with every character outside {@code [a-z0-9_-]} replaced by {@code _}.
     * {@code localId} matches {@code [a-z0-9_-]{1,64}}.
     * <p>
     * Registering the same id again from the same plugin replaces that prompt. A different plugin
     * whose name normalizes to the same namespace throws {@link IllegalStateException} naming both
     * plugins. {@code /nai reload} keeps the prompt. Disabling {@code owner} removes it.
     * The prompt is not written to a file.
     * <p>
     * Code prompts cannot set {@code actions}, {@code dialogue}, or {@code context}. An admin can
     * add those by overriding the same id in {@code prompts.yml} ({@code "quests:intro":}). That
     * file entry replaces the definition. A schema stored on {@code definition} stays the code schema.
     * <p>
     * The id is then usable from {@link #generate}, {@code %ainexus_cached_<id>%}, and
     * {@code /nai test <id>}. {@code /nai prompts} lists it with the plugin name.
     * <pre>{@code
     * NexusAIApi.registerPrompt(this, "intro", PromptDefinition.builder(
     *                 "Write a two-sentence intro for the quest {quest}.")
     *         .format("short")
     *         .maxTokens(120)
     *         .ttl(Duration.ofMinutes(30))
     *         .fallback("A new quest awaits.")
     *         .build());
     * }</pre>
     */
    public static void registerPrompt(Plugin owner, String localId, PromptDefinition definition) {
        if (owner == null || localId == null || definition == null) {
            throw new IllegalArgumentException("owner, localId, and definition are required");
        }
        ApiPromptRegistry.get().register(owner, localId, definition);
    }

    /**
     * Removes {@code namespace:localId} when this plugin owns it.
     * {@code false} when the arguments are missing, the id is invalid, or another plugin owns it.
     */
    public static boolean unregisterPrompt(Plugin owner, String localId) {
        if (owner == null || localId == null) {
            return false;
        }
        return ApiPromptRegistry.get().unregister(owner, localId);
    }

    /** Full ids ({@code quests:intro}) registered by {@code owner}. */
    public static List<String> registeredPromptIds(Plugin owner) {
        if (owner == null) {
            return List.of();
        }
        return ApiPromptRegistry.get().ids(owner);
    }
}
