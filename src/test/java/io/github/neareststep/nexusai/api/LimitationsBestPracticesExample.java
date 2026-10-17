package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.api.event.NexusActionEvent;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiling examples for docs/limitations.md. Not executed by the suite except through
 * {@link LimitationsBestPracticesExampleTest}, which exercises the pure helpers.
 */
public final class LimitationsBestPracticesExample {

    private static final Pattern SAFE_TITLE = Pattern.compile("[A-Za-z0-9 .,'-]{1,40}");
    private static final Set<String> ALLOWED_ACTIONS = Set.of("give_iron", "open_shop");

    private LimitationsBestPracticesExample() {
    }

    /** Schema used by the quest draft example. Root must be an object. */
    public static JsonSchema questSchema() {
        return JsonSchema.parse("""
                {"type":"object","additionalProperties":false,
                 "required":["title","goal","reward","action"],
                 "properties":{
                   "title":{"type":"string","maxLength":40},
                   "goal":{"type":"string","maxLength":200},
                   "reward":{"type":"integer","minimum":1,"maximum":1000},
                   "action":{"type":"string","enum":["give_iron","open_shop"]}}}
                """);
    }

    /**
     * Passes world state the model cannot see by itself (coords and block underfoot)
     * through request variables. Call only after those values were read on the thread
     * that owns the player.
     */
    public static void requestQuestDraft(
            Plugin plugin,
            Player player,
            int blockX,
            int blockY,
            int blockZ,
            String blockType
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(blockType, "blockType");
        NexusAIApi.generateJson(plugin, GenerationRequest.template(
                        "Invent a short fetch quest. Player stands at {x},{y},{z} on {block}.")
                        .player(player)
                        .var("x", Integer.toString(blockX))
                        .var("y", Integer.toString(blockY))
                        .var("z", Integer.toString(blockZ))
                        .var("block", blockType)
                        .cacheMode(CacheMode.FRESH)
                        .build(), questSchema())
                .thenAccept(result -> {
                    Optional<AcceptedQuest> quest = acceptQuest(result);
                    if (quest.isEmpty()) {
                        return;
                    }
                    AcceptedQuest draft = quest.get();
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> applyQuest(plugin, draft));
                });
    }

    /**
     * Schema match ({@link JsonGenerationResult#success()}) is not enough. Check types,
     * ranges, allow-lists, and string shape before any world change.
     */
    public static Optional<AcceptedQuest> acceptQuest(JsonGenerationResult result) {
        if (result == null || !result.success()) {
            return Optional.empty();
        }
        return acceptQuestMap(result.asMap().orElse(Map.of()));
    }

    /** Pure checks used by docs and unit tests. */
    public static Optional<AcceptedQuest> acceptQuestMap(Map<String, Object> quest) {
        if (quest == null || quest.isEmpty()) {
            return Optional.empty();
        }
        Object titleObj = quest.get("title");
        Object goalObj = quest.get("goal");
        Object rewardObj = quest.get("reward");
        Object actionObj = quest.get("action");
        if (!(titleObj instanceof String title)
                || !(goalObj instanceof String goal)
                || !(rewardObj instanceof Long reward)
                || !(actionObj instanceof String action)) {
            return Optional.empty();
        }
        if (!SAFE_TITLE.matcher(title).matches()) {
            return Optional.empty();
        }
        if (goal.isBlank() || goal.length() > 200) {
            return Optional.empty();
        }
        if (reward < 1L || reward > 1000L) {
            return Optional.empty();
        }
        String actionId = action.toLowerCase(Locale.ROOT);
        if (!ALLOWED_ACTIONS.contains(actionId)) {
            return Optional.empty();
        }
        return Optional.of(new AcceptedQuest(title, goal, reward, actionId));
    }

    /** Placeholder for the caller's quest registration. Kept for compile completeness. */
    static void applyQuest(Plugin plugin, AcceptedQuest quest) {
        plugin.getLogger().info("Accepted quest draft: " + quest.title() + " / " + quest.action());
    }

    /**
     * Cancels a character action so the command never runs. The model is told
     * {@code refused: blocked by server}. Register with the plugin manager in {@code onEnable}.
     */
    public static final class ActionGuard implements Listener {

        private final Set<String> blockedInArena;

        public ActionGuard(Set<String> blockedInArena) {
            this.blockedInArena = blockedInArena == null ? Set.of() : Set.copyOf(blockedInArena);
        }

        @EventHandler(ignoreCancelled = true)
        public void onAction(NexusActionEvent event) {
            if (event.player() == null) {
                return;
            }
            if (blockedInArena.contains(event.actionName()) && isInArena(event.player())) {
                event.setCancelled(true);
            }
        }

        /** Replace with the caller's arena check. */
        static boolean isInArena(Player player) {
            return player.getWorld() != null && "arena".equalsIgnoreCase(player.getWorld().getName());
        }
    }

    public record AcceptedQuest(String title, String goal, long reward, String action) {
    }
}
