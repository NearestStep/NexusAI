package io.github.neareststep.nexusai.api;

import java.util.OptionalInt;

/**
 * Optional per-call overrides for {@link NexusAIApi#worldContext(org.bukkit.plugin.Plugin,
 * org.bukkit.entity.Player, WorldContextLimits)}. An empty field keeps the value from
 * {@code world-context.*} in {@code config.yml}.
 */
public final class WorldContextLimits {

    private static final WorldContextLimits UNSET = new WorldContextLimits(
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty());

    private final OptionalInt radius;
    private final OptionalInt maxBlocks;
    private final OptionalInt maxBlockTypes;
    private final OptionalInt maxInventoryItems;
    private final OptionalInt maxChars;

    private WorldContextLimits(
            OptionalInt radius,
            OptionalInt maxBlocks,
            OptionalInt maxBlockTypes,
            OptionalInt maxInventoryItems,
            OptionalInt maxChars
    ) {
        this.radius = radius == null ? OptionalInt.empty() : radius;
        this.maxBlocks = maxBlocks == null ? OptionalInt.empty() : maxBlocks;
        this.maxBlockTypes = maxBlockTypes == null ? OptionalInt.empty() : maxBlockTypes;
        this.maxInventoryItems = maxInventoryItems == null ? OptionalInt.empty() : maxInventoryItems;
        this.maxChars = maxChars == null ? OptionalInt.empty() : maxChars;
    }

    /** Every field empty: use config defaults. */
    public static WorldContextLimits unset() {
        return UNSET;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OptionalInt radius() {
        return radius;
    }

    public OptionalInt maxBlocks() {
        return maxBlocks;
    }

    public OptionalInt maxBlockTypes() {
        return maxBlockTypes;
    }

    public OptionalInt maxInventoryItems() {
        return maxInventoryItems;
    }

    public OptionalInt maxChars() {
        return maxChars;
    }

    public static final class Builder {
        private OptionalInt radius = OptionalInt.empty();
        private OptionalInt maxBlocks = OptionalInt.empty();
        private OptionalInt maxBlockTypes = OptionalInt.empty();
        private OptionalInt maxInventoryItems = OptionalInt.empty();
        private OptionalInt maxChars = OptionalInt.empty();

        private Builder() {
        }

        public Builder radius(int value) {
            this.radius = OptionalInt.of(value);
            return this;
        }

        public Builder maxBlocks(int value) {
            this.maxBlocks = OptionalInt.of(value);
            return this;
        }

        public Builder maxBlockTypes(int value) {
            this.maxBlockTypes = OptionalInt.of(value);
            return this;
        }

        public Builder maxInventoryItems(int value) {
            this.maxInventoryItems = OptionalInt.of(value);
            return this;
        }

        public Builder maxChars(int value) {
            this.maxChars = OptionalInt.of(value);
            return this;
        }

        public WorldContextLimits build() {
            return new WorldContextLimits(radius, maxBlocks, maxBlockTypes, maxInventoryItems, maxChars);
        }
    }
}
