package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.api.event.NexusActionEvent;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitationsBestPracticesExampleTest {

    @Test
    void questSchemaParses() {
        JsonSchema schema = LimitationsBestPracticesExample.questSchema();
        assertTrue(schema.json().contains("\"title\""));
        assertTrue(schema.json().contains("\"reward\""));
    }

    @Test
    void acceptQuestMapRequiresSuccessShapeAndAllowList() {
        Optional<LimitationsBestPracticesExample.AcceptedQuest> ok =
                LimitationsBestPracticesExample.acceptQuestMap(Map.of(
                        "title", "Iron for the forge",
                        "goal", "Bring three iron ore.",
                        "reward", 25L,
                        "action", "give_iron"));
        assertTrue(ok.isPresent());
        assertEquals(25L, ok.get().reward());
        assertEquals("give_iron", ok.get().action());

        assertTrue(LimitationsBestPracticesExample.acceptQuestMap(Map.of(
                "title", "Bad <click:run_command:/op>",
                "goal", "x",
                "reward", 25L,
                "action", "give_iron")).isEmpty());
        assertTrue(LimitationsBestPracticesExample.acceptQuestMap(Map.of(
                "title", "Ok",
                "goal", "x",
                "reward", 0L,
                "action", "give_iron")).isEmpty());
        assertTrue(LimitationsBestPracticesExample.acceptQuestMap(Map.of(
                "title", "Ok",
                "goal", "x",
                "reward", 25L,
                "action", "summon_wither")).isEmpty());
        assertTrue(LimitationsBestPracticesExample.acceptQuest(null).isEmpty());
    }

    @Test
    void actionGuardCancelsBlockedArenaAction() {
        LimitationsBestPracticesExample.ActionGuard guard =
                new LimitationsBestPracticesExample.ActionGuard(Set.of("give_iron"));
        NexusActionEvent blocked = new NexusActionEvent(
                playerInWorld("arena"), "blacksmith", "give_iron", "give Steve iron_ingot 1", true, 7L);
        guard.onAction(blocked);
        assertTrue(blocked.isCancelled());

        NexusActionEvent allowed = new NexusActionEvent(
                playerInWorld("world"), "blacksmith", "give_iron", "give Steve iron_ingot 1", true, 8L);
        guard.onAction(allowed);
        assertFalse(allowed.isCancelled());

        NexusActionEvent otherAction = new NexusActionEvent(
                playerInWorld("arena"), "blacksmith", "open_shop", "warp shop", false, 9L);
        guard.onAction(otherAction);
        assertFalse(otherAction.isCancelled());
    }

    @Test
    void exampleSourceMatchesDocsContract() throws Exception {
        String source = Files.readString(Path.of(
                "src/test/java/io/github/neareststep/nexusai/api/LimitationsBestPracticesExample.java"));
        assertTrue(source.contains("NexusActionEvent"));
        assertTrue(source.contains("setCancelled(true)"));
        assertTrue(source.contains("generateJson"));
        assertTrue(source.contains("acceptQuest"));
        assertTrue(source.contains(".var(\"block\""));
    }

    private static Player playerInWorld(String worldName) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return worldName;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if ("getWorld".equals(method.getName())) {
                        return world;
                    }
                    if ("getName".equals(method.getName())) {
                        return "Steve";
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("test");
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
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
        return null;
    }
}
