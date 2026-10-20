package dev.livelynpcs.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickActionAndMobsTest {

    @Test
    void clickActionsReadTheirKindAndFillInThePlayer() {
        ClickAction a = ClickAction.parse("player:/warp shop {player}");
        assertEquals(ClickAction.Type.PLAYER, a.type());
        assertEquals("warp shop Steve", a.forPlayer("Steve"));
        assertEquals("player:warp shop {player}", a.toString());
        assertEquals(ClickAction.Type.CONSOLE, ClickAction.parse("Console: give {player} bread").type());
        ClickAction m = ClickAction.parse("message:<green>Hello {player}! Prices: 5:1");
        assertEquals(ClickAction.Type.MESSAGE, m.type());
        assertEquals("<green>Hello Steve! Prices: 5:1", m.forPlayer("Steve"));
        assertEquals("<green>Hello \\<b>! Prices: 5:1", m.forPlayer("<b>"), "a name never becomes a tag");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("say hi")).getMessage()
                .contains("player:, console: or message:"));
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("player:"));
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("message:   "));
    }

    @Test
    void villagerVariants() {
        assertArrayEquals(new String[] {"plains", "none"}, Mobs.villager(""));
        assertArrayEquals(new String[] {"plains", "farmer"}, Mobs.villager("plains/farmer"));
        assertArrayEquals(new String[] {"desert", "none"}, Mobs.villager("desert"));
        assertArrayEquals(new String[] {"plains", "librarian"}, Mobs.villager("librarian"));
        assertArrayEquals(new String[] {"snow", "cleric"}, Mobs.villager("Snow/Cleric"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Mobs.villager("moon/farmer")).getMessage().contains("villager type"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Mobs.villager("plains/wizard")).getMessage().contains("profession"));
    }

    @Test
    void mobSizes() {
        assertArrayEquals(new double[] {1.8, 1.62}, Mobs.size("player"));
        assertArrayEquals(new double[] {0.98, 0.8125}, Mobs.size("copper_golem"));
        assertArrayEquals(new double[] {1.95, 1.62}, Mobs.size("something_new"), "unknown mobs are villager sized");
    }
}
