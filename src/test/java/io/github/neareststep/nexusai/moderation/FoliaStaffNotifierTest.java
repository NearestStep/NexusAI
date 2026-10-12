package io.github.neareststep.nexusai.moderation;

import io.github.neareststep.nexusai.context.RegionOwnership;
import io.github.neareststep.nexusai.i18n.MessageService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaStaffNotifierTest {

    @AfterEach
    void resetRegionOwnership() {
        RegionOwnership.reset();
    }

    @Test
    void permissionIsReadOnlyAfterOneHopWhenTheRecipientIsNotOwned() {
        Harness harness = new Harness();
        RegionOwnership.install(player -> harness.inEntity.get());
        harness.notifier.flagged("Steve", "hello", "toxicity", "slur");
        assertEquals(1, harness.globalRuns.get());
        assertEquals(1, harness.entityHops.get());
        assertEquals(1, harness.permissionInside.get());
        assertEquals(0, harness.permissionOutside.get());
        assertEquals(1, harness.sent.get());
        assertTrue(harness.sentText.get().contains("Steve"));
    }

    @Test
    void permissionIsReadInlineWhenTheRecipientIsOwned() {
        Harness harness = new Harness();
        RegionOwnership.install(player -> true);
        harness.notifier.flagged("Steve", "hello", "toxicity", "slur");
        assertEquals(1, harness.globalRuns.get());
        assertEquals(0, harness.entityHops.get());
        assertEquals(0, harness.permissionInside.get());
        assertEquals(1, harness.permissionOutside.get());
        assertEquals(1, harness.sent.get());
    }

    private static final class Harness {
        private final AtomicBoolean inEntity = new AtomicBoolean();
        private final AtomicInteger entityHops = new AtomicInteger();
        private final AtomicInteger globalRuns = new AtomicInteger();
        private final AtomicInteger permissionInside = new AtomicInteger();
        private final AtomicInteger permissionOutside = new AtomicInteger();
        private final AtomicInteger sent = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<String> sentText = new java.util.concurrent.atomic.AtomicReference<>();
        private final FoliaStaffNotifier notifier;

        private Harness() {
            Player recipient = recipient();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("moderation.notify", "flag {player}");
            MessageService messages = new MessageService(yaml, new YamlConfiguration(), "en");
            notifier = new FoliaStaffNotifier(
                    plugin(),
                    () -> messages,
                    Logger.getLogger("staff-hop"),
                    () -> List.of(recipient));
        }

        private Player recipient() {
            EntityScheduler scheduler = (EntityScheduler) java.lang.reflect.Proxy.newProxyInstance(
                    EntityScheduler.class.getClassLoader(),
                    new Class<?>[]{EntityScheduler.class},
                    (proxy, method, args) -> {
                        if ("run".equals(method.getName()) && args != null && args.length >= 2
                                && args[1] instanceof Consumer<?> consumer) {
                            entityHops.incrementAndGet();
                            inEntity.set(true);
                            try {
                                consumer.accept(null);
                            } finally {
                                inEntity.set(false);
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
                        case "getScheduler" -> scheduler;
                        case "isOnline" -> true;
                        case "hasPermission" -> {
                            if (inEntity.get()) {
                                permissionInside.incrementAndGet();
                            } else {
                                permissionOutside.incrementAndGet();
                            }
                            yield "nexusai.moderation.notify".equals(args[0]);
                        }
                        case "sendMessage" -> {
                            sent.incrementAndGet();
                            if (args != null && args.length > 0 && args[0] instanceof String text) {
                                sentText.set(text);
                            }
                            yield null;
                        }
                        case "toString" -> "staff";
                        default -> {
                            if (method.getReturnType() == boolean.class) {
                                yield false;
                            }
                            yield null;
                        }
                    });
        }

        private Plugin plugin() {
            GlobalRegionScheduler global = (GlobalRegionScheduler) java.lang.reflect.Proxy.newProxyInstance(
                    GlobalRegionScheduler.class.getClassLoader(),
                    new Class<?>[]{GlobalRegionScheduler.class},
                    (proxy, method, args) -> {
                        if ("run".equals(method.getName()) && args != null && args.length >= 2
                                && args[1] instanceof Consumer<?> consumer) {
                            globalRuns.incrementAndGet();
                            consumer.accept(null);
                        }
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        return null;
                    });
            Server server = (Server) java.lang.reflect.Proxy.newProxyInstance(
                    Server.class.getClassLoader(),
                    new Class<?>[]{Server.class},
                    (proxy, method, args) -> {
                        if ("getGlobalRegionScheduler".equals(method.getName())) {
                            return global;
                        }
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        if (method.getReturnType() == int.class) {
                            return 0;
                        }
                        return null;
                    });
            return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                    Plugin.class.getClassLoader(),
                    new Class<?>[]{Plugin.class},
                    (proxy, method, args) -> {
                        if ("getServer".equals(method.getName())) {
                            return server;
                        }
                        if ("getLogger".equals(method.getName())) {
                            return Logger.getLogger("staff-hop");
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
    }
}
