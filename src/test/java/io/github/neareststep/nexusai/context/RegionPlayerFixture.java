package io.github.neareststep.nexusai.context;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Players for region-ownership tests. A forbidden read fails the test. */
public final class RegionPlayerFixture {

    private RegionPlayerFixture() {
    }

    public static Player throwing(AtomicBoolean touched) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "throwing-player";
                    }
                    if (touched != null) {
                        touched.set(true);
                    }
                    throw new AssertionError("player state read: " + method.getName());
                });
    }

    public static Player named(String name, String worldName) {
        return named(name, worldName, null);
    }

    public static Player named(String name, String worldName, Object scheduler) {
        return named(UUID.fromString("11111111-1111-1111-1111-111111111111"), name, worldName, scheduler);
    }

    public static Player named(UUID id, String name, String worldName, Object scheduler) {
        Block block = (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> {
                    if ("getBiome".equals(method.getName())) {
                        return Biome.PLAINS;
                    }
                    return defaultValue(method.getReturnType());
                });
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> worldName;
                    case "getTime" -> 6_000L;
                    case "hasStorm" -> false;
                    case "isThundering" -> false;
                    case "getBlockAt" -> block;
                    case "toString" -> worldName;
                    default -> defaultValue(method.getReturnType());
                });
        Location location = new Location(world, 0, 64, 0);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
                    case "getLocation" -> location;
                    case "getScheduler" -> scheduler;
                    case "isOnline" -> true;
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
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
