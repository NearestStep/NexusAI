package io.github.neareststep.nexusai.context;

import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plain data captured on the player's region thread. Holds no Bukkit references, so sorting and
 * formatting can run on any worker afterward. Region-thread work is only location, biome,
 * inventory material keys, and a capped nearby block sample — no sorting and no string assembly.
 */
public final class WorldContextSnapshot {

    private final String playerName;
    private final String worldName;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final String biome;
    private final Map<String, Integer> inventoryTotals;
    private final Map<String, Integer> nearbyTotals;
    private final int blocksExamined;
    private final int radius;

    WorldContextSnapshot(
            String playerName,
            String worldName,
            int blockX,
            int blockY,
            int blockZ,
            String biome,
            Map<String, Integer> inventoryTotals,
            Map<String, Integer> nearbyTotals,
            int blocksExamined,
            int radius
    ) {
        this.playerName = playerName == null ? "" : playerName;
        this.worldName = worldName == null ? "" : worldName;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.biome = biome == null ? "" : biome;
        this.inventoryTotals = inventoryTotals == null || inventoryTotals.isEmpty()
                ? Map.of()
                : Map.copyOf(inventoryTotals);
        this.nearbyTotals = nearbyTotals == null || nearbyTotals.isEmpty()
                ? Map.of()
                : Map.copyOf(nearbyTotals);
        this.blocksExamined = Math.max(0, blocksExamined);
        this.radius = Math.max(0, radius);
    }

    /**
     * Reads only what the formatter needs. Call only when this thread owns {@code player}.
     * Returns null when {@code player} is null or the world is missing.
     */
    public static WorldContextSnapshot capture(Player player, WorldContextSettings limits) {
        if (player == null || limits == null) {
            return null;
        }
        Location location;
        World world;
        try {
            location = player.getLocation();
            world = location == null ? null : location.getWorld();
        } catch (Throwable ignored) {
            return null;
        }
        if (world == null || location == null) {
            return null;
        }
        int bx = location.getBlockX();
        int by = location.getBlockY();
        int bz = location.getBlockZ();
        String biome = "";
        try {
            biome = ContextVariables.biomeKey(location.getBlock().getBiome());
        } catch (Throwable ignored) {
            biome = "";
        }
        Map<String, Integer> inventory = readInventory(player, limits.maxInventoryItems());
        NearbyScan scan = readNearby(world, bx, by, bz, limits.radius(), limits.maxBlocks());
        String name;
        try {
            name = player.getName();
        } catch (Throwable ignored) {
            name = "";
        }
        return new WorldContextSnapshot(
                name,
                safeName(world),
                bx,
                by,
                bz,
                biome,
                inventory,
                scan.totals(),
                scan.examined(),
                limits.radius());
    }

    public String playerName() {
        return playerName;
    }

    public String worldName() {
        return worldName;
    }

    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
    }

    public String biome() {
        return biome;
    }

    /** Unsorted material totals. The formatter ranks and truncates these off the region thread. */
    public Map<String, Integer> inventoryTotals() {
        return inventoryTotals;
    }

    /** Unsorted nearby material totals. Ranked off the region thread. */
    public Map<String, Integer> nearbyTotals() {
        return nearbyTotals;
    }

    public int blocksExamined() {
        return blocksExamined;
    }

    public int radius() {
        return radius;
    }

    private static Map<String, Integer> readInventory(Player player, int maxItems) {
        if (maxItems <= 0) {
            return Map.of();
        }
        PlayerInventory inventory;
        try {
            inventory = player.getInventory();
        } catch (Throwable ignored) {
            return Map.of();
        }
        if (inventory == null) {
            return Map.of();
        }
        Map<String, Integer> totals = new HashMap<>();
        addStacks(totals, inventory.getContents());
        addStacks(totals, inventory.getArmorContents());
        try {
            addStack(totals, inventory.getItemInOffHand());
        } catch (Throwable ignored) {
            // Older or incomplete mocks may omit the off-hand accessor.
        }
        return totals;
    }

    private static void addStacks(Map<String, Integer> totals, ItemStack[] stacks) {
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            addStack(totals, stack);
        }
    }

    private static void addStack(Map<String, Integer> totals, ItemStack stack) {
        if (stack == null) {
            return;
        }
        Material type;
        int amount;
        try {
            type = stack.getType();
            amount = stack.getAmount();
        } catch (Throwable ignored) {
            return;
        }
        if (type == null || amount <= 0 || isAir(type)) {
            return;
        }
        String key = materialKey(type);
        if (key.isEmpty()) {
            return;
        }
        totals.merge(key, amount, Integer::sum);
    }

    private static NearbyScan readNearby(
            World world,
            int cx,
            int cy,
            int cz,
            int radius,
            int maxBlocks
    ) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        int examined = 0;
        int r = Math.max(0, radius);
        int cap = Math.max(1, maxBlocks);
        outer:
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (examined >= cap) {
                        break outer;
                    }
                    examined++;
                    Block block;
                    try {
                        block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (block == null) {
                        continue;
                    }
                    Material type;
                    try {
                        type = block.getType();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (type == null || isAir(type)) {
                        continue;
                    }
                    String key = materialKey(type);
                    if (key.isEmpty()) {
                        continue;
                    }
                    totals.merge(key, 1, Integer::sum);
                }
            }
        }
        return new NearbyScan(totals, examined);
    }

    private static boolean isAir(Material type) {
        try {
            return type.isAir();
        } catch (Throwable ignored) {
            String name = type.name();
            return name != null && name.endsWith("AIR");
        }
    }

    /**
     * Path of a material key. Stored as {@link Keyed} so the class file does not depend on
     * {@code Material.getKey()} linkage shape across Paper versions.
     */
    static String materialKey(Object material) {
        if (!(material instanceof Keyed keyed)) {
            return "";
        }
        try {
            NamespacedKey key = keyed.getKey();
            if (key == null) {
                return "";
            }
            String path = key.getKey();
            if (path == null || path.isEmpty()) {
                return "";
            }
            return path.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeName(World world) {
        try {
            String name = world.getName();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public record Count(String id, int amount) {
        public Count {
            id = id == null ? "" : id;
            amount = Math.max(0, amount);
        }
    }

    private record NearbyScan(Map<String, Integer> totals, int examined) {
    }
}
