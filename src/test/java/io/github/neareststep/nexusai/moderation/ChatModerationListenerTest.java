package io.github.neareststep.nexusai.moderation;

import io.github.neareststep.nexusai.context.RegionOwnership;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModerationListenerTest {

    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @AfterEach
    void resetRegionOwnership() {
        RegionOwnership.reset();
    }

    @Test
    void bypassPermissionIsReadOnlyInsideTheHopWhenTheRegionIsNotOwned() {
        AtomicBoolean inTask = new AtomicBoolean();
        AtomicInteger hops = new AtomicInteger();
        AtomicInteger permissionInside = new AtomicInteger();
        AtomicInteger permissionOutside = new AtomicInteger();
        AtomicReference<Boolean> bypass = new AtomicReference<>();
        Player player = player(inTask, hops, permissionInside, permissionOutside, true);
        RegionOwnership.install(ignored -> inTask.get());
        AsyncChatEvent event = event(player);
        listener(bypass).onChat(event);
        assertEquals(1, hops.get());
        assertEquals(1, permissionInside.get());
        assertEquals(0, permissionOutside.get());
        assertEquals(Boolean.TRUE, bypass.get());
        assertTrue(event.isAsynchronous());
        assertFalse(event.isCancelled());
    }

    @Test
    void submitRunsInlineWhenTheRegionIsOwned() {
        AtomicBoolean inTask = new AtomicBoolean();
        AtomicInteger hops = new AtomicInteger();
        AtomicInteger permissionInside = new AtomicInteger();
        AtomicInteger permissionOutside = new AtomicInteger();
        AtomicReference<Boolean> bypass = new AtomicReference<>();
        Player player = player(inTask, hops, permissionInside, permissionOutside, true);
        RegionOwnership.install(ignored -> true);
        AsyncChatEvent event = event(player);
        listener(bypass).onChat(event);
        assertEquals(0, hops.get());
        assertEquals(0, permissionInside.get());
        assertEquals(1, permissionOutside.get());
        assertEquals(Boolean.TRUE, bypass.get());
        assertTrue(event.isAsynchronous());
        assertFalse(event.isCancelled());
    }

    private static ChatModerationListener listener(AtomicReference<Boolean> bypass) {
        return new ChatModerationListener(plugin(), new ChatModerationListener.Gate() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void submit(UUID playerId, String name, String message, boolean allowed) {
                assertEquals(STEVE, playerId);
                assertEquals("Steve", name);
                assertEquals("hello there", message);
                bypass.set(allowed);
            }
        }, Logger.getLogger("chat-hop"));
    }

    private static AsyncChatEvent event(Player player) {
        return new AsyncChatEvent(
                true, player, Set.of(), null, Component.text("hello there"), Component.text("hello there"), null);
    }

    private static Player player(
            AtomicBoolean inTask,
            AtomicInteger hops,
            AtomicInteger permissionInside,
            AtomicInteger permissionOutside,
            boolean bypass
    ) {
        EntityScheduler scheduler = (EntityScheduler) java.lang.reflect.Proxy.newProxyInstance(
                EntityScheduler.class.getClassLoader(),
                new Class<?>[]{EntityScheduler.class},
                (proxy, method, args) -> {
                    if ("run".equals(method.getName()) && args != null && args.length >= 2
                            && args[1] instanceof Consumer<?> consumer) {
                        hops.incrementAndGet();
                        inTask.set(true);
                        try {
                            consumer.accept(null);
                        } finally {
                            inTask.set(false);
                        }
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> STEVE;
                    case "getName" -> "Steve";
                    case "isOnline" -> true;
                    case "getScheduler" -> scheduler;
                    case "hasPermission" -> {
                        if (inTask.get()) {
                            permissionInside.incrementAndGet();
                        } else {
                            permissionOutside.incrementAndGet();
                        }
                        yield "nexusai.moderation.bypass".equals(args[0]) && bypass;
                    }
                    case "toString" -> "Steve";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Plugin plugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("chat-hop");
                    }
                    if ("getName".equals(method.getName())) {
                        return "NexusAI";
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
