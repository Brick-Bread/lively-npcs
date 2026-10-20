package dev.livelynpcs.npc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which NPC beckons a player: between beckon-from and beckon-to, faced within 60 degrees, the one faced most. */
class BeckonTest {

    private static final double FROM = 6, TO = 16;

    /** An NPC's eyes this far away in the direction of yaw (Minecraft: 0 south +z, 90 west -x), at eye height. */
    private static Beckon.Spot at(double distance, double yaw) {
        double r = Math.toRadians(yaw);
        return new Beckon.Spot(-distance * Math.sin(r), 1.62, distance * Math.cos(r));
    }

    private static int pick(float facing, Beckon.Spot... spots) {
        return Beckon.pick(0, 1.62, 0, facing, List.of(spots), FROM, TO);
    }

    @Test
    void onlyBetweenFromAndTo() {
        assertEquals(-1, pick(0, at(5, 0)), "too near: it would greet instead");
        assertEquals(0, pick(0, at(6, 0)));
        assertEquals(0, pick(0, at(16, 0)));
        assertEquals(-1, pick(0, at(16.5, 0)), "too far");
    }

    @Test
    void onlyWhenFacingItWithinSixtyDegrees() {
        assertEquals(0, pick(0, at(10, 59)));
        assertEquals(-1, pick(0, at(10, 61)));
        assertEquals(-1, pick(0, at(10, 180)), "behind them");
        // Across the -180/180 seam.
        assertEquals(0, pick(170, at(10, -170)));
    }

    @Test
    void picksTheOneTheyFaceMostThenTheNearer() {
        assertEquals(1, pick(0, at(8, 30), at(14, 5), at(10, -20)));
        assertEquals(1, pick(90, at(12, 100), at(9, 80), at(7, 150)));
        assertEquals(0, pick(0, at(9, 10), at(12, 10)), "a tie goes to the nearer");
        assertEquals(1, pick(0, at(12, 10), at(9, 10)));
    }

    @Test
    void heightCountsInTheDistance() {
        // 10 blocks across and 13 up is more than 16 eyes to eyes.
        assertEquals(-1, Beckon.pick(0, 1.62, 0, 0, List.of(new Beckon.Spot(0, 14.62, 10)), FROM, TO));
    }

    @Test
    void offWhenTheRangeIsEmpty() {
        assertEquals(-1, Beckon.pick(0, 1.62, 0, 0, List.of(at(10, 0)), 0, 16));
        assertEquals(-1, Beckon.pick(0, 1.62, 0, 0, List.of(at(10, 0)), 6, 0));
        assertEquals(-1, pick(0));
    }
}
