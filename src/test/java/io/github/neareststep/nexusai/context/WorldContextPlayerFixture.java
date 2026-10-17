package io.github.neareststep.nexusai.context;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/** Players with inventory and a small block cube for world-context tests. */
public final class WorldContextPlayerFixture {

    private WorldContextPlayerFixture() {
    }

    public static Player plains(
            String name,
            Map<String, Integer> inventory,
            IntFunction<Material> blockAtRelative,
            AtomicInteger blockReads
    ) {
        return plains(name, inventory, blockAtRelative, blockReads, null);
    }

    public static Player plains(
            String name,
            Map<String, Integer> inventory,
            IntFunction<Material> blockAtRelative,
            AtomicInteger blockReads,
            Object scheduler
    ) {
        ItemStack[] contents = stacks(inventory);
        PlayerInventory bag = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getContents" -> contents;
                    case "getArmorContents" -> new ItemStack[0];
                    case "getItemInOffHand" -> null;
                    case "toString" -> "inv";
                    default -> defaultValue(method.getReturnType());
                });
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return "world";
                    }
                    if ("getBlockAt".equals(method.getName())) {
                        int x;
                        int y;
                        int z;
                        if (args != null && args.length == 1 && args[0] instanceof Location at) {
                            x = at.getBlockX();
                            y = at.getBlockY();
                            z = at.getBlockZ();
                        } else if (args != null && args.length >= 3) {
                            x = ((Number) args[0]).intValue();
                            y = ((Number) args[1]).intValue();
                            z = ((Number) args[2]).intValue();
                        } else {
                            return block(Material.AIR);
                        }
                        if (blockReads != null) {
                            blockReads.incrementAndGet();
                        }
                        int dx = x;
                        int dy = y - 64;
                        int dz = z;
                        Material type = blockAtRelative == null
                                ? Material.AIR
                                : blockAtRelative.apply(pack(dx, dy, dz));
                        return block(type == null ? Material.AIR : type);
                    }
                    if ("toString".equals(method.getName())) {
                        return "world";
                    }
                    return defaultValue(method.getReturnType());
                });
        Location location = new Location(world, 0.5, 64.0, 0.5);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> UUID.fromString("22222222-2222-2222-2222-222222222222");
                    case "getWorld" -> world;
                    case "getLocation" -> location;
                    case "getInventory" -> bag;
                    case "getScheduler" -> scheduler;
                    case "isOnline" -> true;
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    public static IntFunction<Material> cube(Material fill, Material center) {
        return packed -> {
            int dx = (short) (packed >> 16);
            int dy = (byte) ((packed >> 8) & 0xff);
            int dz = (byte) (packed & 0xff);
            if (dx == 0 && dy == 0 && dz == 0) {
                return center;
            }
            return fill;
        };
    }

    private static int pack(int dx, int dy, int dz) {
        return ((dx & 0xffff) << 16) | ((dy & 0xff) << 8) | (dz & 0xff);
    }

    private static Block block(Material type) {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> type;
                    case "getBiome" -> Biome.PLAINS;
                    case "toString" -> String.valueOf(type);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ItemStack[] stacks(Map<String, Integer> inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return new ItemStack[0];
        }
        Map<String, Integer> copy = new HashMap<>(inventory);
        ItemStack[] out = new ItemStack[copy.size()];
        int i = 0;
        for (Map.Entry<String, Integer> entry : copy.entrySet()) {
            Material material = Material.matchMaterial(entry.getKey());
            if (material == null) {
                material = Material.STONE;
            }
            int amount = entry.getValue() == null ? 0 : entry.getValue();
            out[i++] = new ItemStack(material, Math.max(0, amount));
        }
        return out;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
