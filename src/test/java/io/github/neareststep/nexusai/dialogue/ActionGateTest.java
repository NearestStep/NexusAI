package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGateTest {

    private final ZoneId zone = ZoneId.of("UTC");
    private final UUID player = UUID.randomUUID();

    @Test
    void permissionCooldownAndDailyCapBlockBeforeTheCommand() {
        CharacterAction action = new CharacterAction(
                "give_iron", "Give one iron ingot.", "give {player} iron_ingot 1", true, 60, 1, "nexusai.action.give_iron");
        ActionGate gate = new ActionGate();
        long now = 1_000_000L;

        assertFalse(gate.admit(action, "blacksmith", player, false, now, zone).allowed());
        assertEquals("permission", gate.admit(action, "blacksmith", player, false, now, zone).reason());

        assertTrue(gate.admit(action, "blacksmith", player, true, now, zone).allowed());
        gate.record(action, "blacksmith", player, now, zone);

        ActionGate.Decision cooled = gate.admit(action, "blacksmith", player, true, now + 1_000L, zone);
        assertFalse(cooled.allowed());
        assertEquals("cooldown", cooled.reason());

        CharacterAction dailyOnly = new CharacterAction(
                "give_iron", "Give one iron ingot.", "give {player} iron_ingot 1", true, 0, 1, null);
        ActionGate days = new ActionGate();
        assertTrue(days.admit(dailyOnly, "blacksmith", player, true, now, zone).allowed());
        days.record(dailyOnly, "blacksmith", player, now, zone);
        assertEquals("daily limit", days.admit(dailyOnly, "blacksmith", player, true, now + 5_000L, zone).reason());
        long nextDay = java.time.LocalDate.of(2026, 1, 2).atStartOfDay(zone).toInstant().toEpochMilli();
        assertTrue(days.admit(dailyOnly, "blacksmith", player, true, nextDay, zone).allowed());
    }

    @Test
    void commandTemplateIgnoresModelArgumentsAndUnsafeNames() {
        assertEquals("give Steve iron_ingot 1", ActionCommands.render("give {player} iron_ingot 1", "Steve", UUID.randomUUID()));
        assertEquals("give Steve iron_ingot 1", ActionCommands.render("/give {player} iron_ingot 1", "Steve", null));
        assertNull(ActionCommands.render("give {player} diamond", "Steve; op Steve", null));
        assertNull(ActionCommands.render("give {player} diamond\nop Steve", "Steve", null));
        assertEquals("give {player_name} iron_ingot 1", ActionCommands.render("give {player_name} iron_ingot 1", "Steve", null));
    }
}
