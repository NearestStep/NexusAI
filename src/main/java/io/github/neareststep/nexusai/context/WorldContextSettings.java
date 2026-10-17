package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.WorldContextLimits;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * {@code world-context.*} from {@code config.yml}. Missing keys use these defaults until
 * {@code ConfigMerger} appends them. Disabled by default so an upgrade does not change prompts
 * or spend region-thread work until an admin turns the feature on.
 * <p>
 * Token size (documented): the body is capped at {@link #maxChars()} code points. NexusAI
 * estimates tokens as {@code ceil(codePoints / 4)} (same as missing usage). At the defaults
 * below the worst-case body is 400 code points ≈ 100 tokens. A typical plains scene with a
 * short inventory is about 120–180 code points ≈ 30–45 tokens. The {@code World:} header adds
 * a few more characters outside that cap when the caller keeps the header.
 * <p>
 * Region-thread budget: only raw world/inventory reads run there. Sorting and string assembly
 * run on {@code nexusai-context-*}. {@link #cooldownMillis()} skips a fresh read and returns the
 * last block for that player when the previous collect was more recent than the cooldown.
 */
public final class WorldContextSettings {

    public static final int DEFAULT_RADIUS = 2;
    public static final int DEFAULT_MAX_BLOCKS = 125;
    public static final int DEFAULT_MAX_BLOCK_TYPES = 16;
    public static final int DEFAULT_MAX_INVENTORY_ITEMS = 36;
    public static final int DEFAULT_MAX_CHARS = 400;
    /** Default per-player minimum interval between fresh region reads. */
    public static final int DEFAULT_COOLDOWN_MILLIS = 250;
    /** Worst-case body tokens at {@link #DEFAULT_MAX_CHARS} using {@code ceil(chars / 4)}. */
    public static final int DOCUMENTED_MAX_BODY_TOKENS = 100;

    private final boolean enabled;
    private final int radius;
    private final int maxBlocks;
    private final int maxBlockTypes;
    private final int maxInventoryItems;
    private final int maxChars;
    private final int cooldownMillis;

    public WorldContextSettings(
            boolean enabled,
            int radius,
            int maxBlocks,
            int maxBlockTypes,
            int maxInventoryItems,
            int maxChars,
            int cooldownMillis
    ) {
        this.enabled = enabled;
        this.radius = radius;
        this.maxBlocks = maxBlocks;
        this.maxBlockTypes = maxBlockTypes;
        this.maxInventoryItems = maxInventoryItems;
        this.maxChars = maxChars;
        this.cooldownMillis = cooldownMillis;
    }

    public static WorldContextSettings defaults() {
        return new WorldContextSettings(
                false,
                DEFAULT_RADIUS,
                DEFAULT_MAX_BLOCKS,
                DEFAULT_MAX_BLOCK_TYPES,
                DEFAULT_MAX_INVENTORY_ITEMS,
                DEFAULT_MAX_CHARS,
                DEFAULT_COOLDOWN_MILLIS);
    }

    public static WorldContextSettings read(FileConfiguration config) {
        if (config == null) {
            return defaults();
        }
        return new WorldContextSettings(
                config.getBoolean("world-context.enabled", false),
                clamp(config.getInt("world-context.radius", DEFAULT_RADIUS), 0, 8),
                clamp(config.getInt("world-context.max-blocks", DEFAULT_MAX_BLOCKS), 1, 1331),
                clamp(config.getInt("world-context.max-block-types", DEFAULT_MAX_BLOCK_TYPES), 1, 64),
                clamp(config.getInt("world-context.max-inventory-items", DEFAULT_MAX_INVENTORY_ITEMS), 0, 54),
                clamp(config.getInt("world-context.max-chars", DEFAULT_MAX_CHARS), 40, 2000),
                clamp(config.getInt("world-context.cooldown-millis", DEFAULT_COOLDOWN_MILLIS), 0, 5000)
        );
    }

    /**
     * Config values with any non-empty fields from {@code overrides} applied, then clamped to
     * the same ranges as {@link #read}. Cooldown always comes from config (not overridable).
     */
    public WorldContextSettings resolve(WorldContextLimits overrides) {
        if (overrides == null) {
            return this;
        }
        return new WorldContextSettings(
                enabled,
                overrides.radius().isPresent()
                        ? clamp(overrides.radius().getAsInt(), 0, 8)
                        : radius,
                overrides.maxBlocks().isPresent()
                        ? clamp(overrides.maxBlocks().getAsInt(), 1, 1331)
                        : maxBlocks,
                overrides.maxBlockTypes().isPresent()
                        ? clamp(overrides.maxBlockTypes().getAsInt(), 1, 64)
                        : maxBlockTypes,
                overrides.maxInventoryItems().isPresent()
                        ? clamp(overrides.maxInventoryItems().getAsInt(), 0, 54)
                        : maxInventoryItems,
                overrides.maxChars().isPresent()
                        ? clamp(overrides.maxChars().getAsInt(), 40, 2000)
                        : maxChars,
                cooldownMillis
        );
    }

    public boolean enabled() {
        return enabled;
    }

    /** Inclusive cube radius around the player feet block. 0 reads only that block. */
    public int radius() {
        return radius;
    }

    /** Hard cap on block samples examined on the region thread. */
    public int maxBlocks() {
        return maxBlocks;
    }

    /** How many distinct nearby materials appear in the prompt line. */
    public int maxBlockTypes() {
        return maxBlockTypes;
    }

    /** How many distinct inventory materials appear in the prompt line. */
    public int maxInventoryItems() {
        return maxInventoryItems;
    }

    /** Code-point cap for the body (without the {@code World:} header). */
    public int maxChars() {
        return maxChars;
    }

    /**
     * Minimum millis between fresh region reads for one player. {@code 0} disables. Within the
     * window, {@link WorldContextService} returns the last formatted block without reading the world.
     */
    public int cooldownMillis() {
        return cooldownMillis;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
