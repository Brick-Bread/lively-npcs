package dev.livelynpcs.rig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the model looks for one viewer: head first, then the body. Stepped every 2 ticks. */
class GazeTest {

    private static final float TURN = Gaze.TURN_PER_TICK * 2;

    @Test
    void headFollowsWithinReachAndTheBodyStays() {
        Gaze gaze = new Gaze(0, 2);
        gaze.step(true, 50, 10);
        assertEquals(0, gaze.body(), 1e-4f);
        assertEquals(50, gaze.head(), 1e-4f);
        assertEquals(10, gaze.pitch(), 1e-4f);
        assertTrue(gaze.looking());
    }

    @Test
    void bodyTurnsAfterSomeoneOutOfTheHeadsReach() {
        Gaze gaze = new Gaze(0, 2);
        gaze.step(true, 120, 0);
        // The head stops at its limit and the body sets off, one step's turn at a time.
        assertEquals(TURN, gaze.body(), 1e-4f);
        assertEquals(Gaze.HEAD_YAW, gaze.head(), 1e-4f);
        float last = gaze.body();
        for (int i = 0; i < 30; i++) {
            gaze.step(true, 120, 0);
            assertTrue(gaze.body() - last <= TURN + 1e-4f);
            last = gaze.body();
        }
        // It turns until the head is comfortable again, then stops.
        assertEquals(120 - Gaze.COMFORT, gaze.body(), 1e-4f);
        assertEquals(Gaze.COMFORT, gaze.head(), 1e-4f);
        assertEquals(120 - Gaze.COMFORT, gaze.bodyTurn(), 1e-4f);
    }

    @Test
    void aGlanceInsideTheComfortableRangeNeverTurnsTheBody() {
        Gaze gaze = new Gaze(0, 2);
        for (float yaw = -Gaze.HEAD_YAW; yaw <= Gaze.HEAD_YAW; yaw += 5) gaze.step(true, yaw, 0);
        assertEquals(0, gaze.body(), 1e-4f);
    }

    @Test
    void turnsTheShortWayRound() {
        Gaze gaze = new Gaze(170, 2);
        gaze.step(true, -170, 0);               // 20 degrees round, across the -180/180 seam
        assertEquals(170, gaze.body(), 1e-4f);
        assertEquals(20, gaze.head(), 1e-4f);
        gaze.step(true, -60, 0);                // 130 degrees round the same way: the body follows across the seam
        gaze.step(true, -60, 0);
        assertEquals(0, Look.apart(gaze.body(), 170 + 2 * TURN), 1e-3f);
        assertEquals(Gaze.HEAD_YAW, gaze.head(), 1e-4f);
    }

    @Test
    void goesBackToItsOwnYawWhenNobodyIsNear() {
        Gaze gaze = new Gaze(0, 2);
        for (int i = 0; i < 30; i++) gaze.step(true, 120, -20);
        gaze.step(false, 0, 0);
        assertFalse(gaze.looking());
        assertEquals(0, gaze.pitch(), 1e-4f);
        assertEquals(120 - Gaze.COMFORT - TURN, gaze.body(), 1e-4f);
        assertEquals(-Gaze.HEAD_YAW, gaze.head(), 1e-4f);
        for (int i = 0; i < 30; i++) gaze.step(false, 0, 0);
        assertEquals(0, gaze.body(), 1e-4f);
        assertEquals(0, gaze.head(), 1e-4f);
    }

    @Test
    void pitchStaysWithinReach() {
        Gaze gaze = new Gaze(0, 2);
        gaze.step(true, 0, -80);
        assertEquals(Gaze.PITCH_UP, gaze.pitch(), 1e-4f);
        gaze.step(true, 0, 80);
        assertEquals(Gaze.PITCH_DOWN, gaze.pitch(), 1e-4f);
    }

    @Test
    void turnsAsFastWhateverTheStep() {
        // Stepped every tick it turns half as far a step: the same speed.
        Gaze each = new Gaze(0, 1), every2 = new Gaze(0, 2);
        for (int i = 0; i < 10; i++) {
            each.step(true, 160, 0);
            each.step(true, 160, 0);
            every2.step(true, 160, 0);
        }
        assertEquals(every2.body(), each.body(), 1e-3f);
    }

    @Test
    void resetStandsItAtRest() {
        Gaze gaze = new Gaze(45, 2);
        for (int i = 0; i < 10; i++) gaze.step(true, -100, 30);
        gaze.reset();
        assertEquals(45, gaze.body(), 1e-4f);
        assertEquals(0, gaze.head(), 1e-4f);
        assertEquals(0, gaze.pitch(), 1e-4f);
        assertFalse(gaze.looking());
    }

    @Test
    void lookFollowsMinecraftsCompass() {
        // 0 faces south (+z), 90 west (-x), -90 east (+x), 180 north (-z).
        assertEquals(0f, Look.yaw(0, 1), 1e-4f);
        assertEquals(90f, Look.yaw(-1, 0), 1e-4f);
        assertEquals(-90f, Look.yaw(1, 0), 1e-4f);
        assertEquals(180f, Math.abs(Look.yaw(0, -1)), 1e-4f);
        assertEquals(-45f, Look.pitch(1, 1, 0), 1e-4f);
        assertEquals(45f, Look.pitch(0, -1, 1), 1e-4f);
        assertEquals(20f, Look.apart(170, -170), 1e-4f);
        assertEquals(0f, Look.apart(10, 370), 1e-4f);
        assertEquals(180f, Look.apart(-90, 90), 1e-4f);
        assertEquals(5f, Look.apart(358, 3), 1e-4f);
    }
}
