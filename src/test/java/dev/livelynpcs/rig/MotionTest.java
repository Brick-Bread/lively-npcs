package dev.livelynpcs.rig;

import dev.livelynpcs.anim.Animation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Steps one model for one viewer walking about, and checks the keyframes that would go out as packets. */
class MotionTest {

    /** A keyframe as it would be sent: when, which part, the transformation and the glide. */
    private record Key(int tick, Part part, float[] t, int glide) { }

    private static final float SCALE = 0.9375f, K = SCALE / 16, EYES = 1.62f;
    private static final double LOOK = 8, ANIMATE = 24;
    private static final int KEY = 2, STEP = 2;
    /** The NPC stands at the middle of block 0, 0 facing south (yaw 0). */
    private static final double X = 0.5, Z = 0.5;

    private final Motion motion = new Motion(X, RigPose.EYES * K, Z, 0f, SCALE, false, true, true, 60, KEY, STEP, new Random(7));
    private final Motion.Watch watch = motion.watch();
    private final List<Key> keys = new ArrayList<>();
    private int now;

    /** Steps for this many ticks, the viewer's eyes where eyes says at each step. */
    private void run(int ticks, IntFunction<double[]> eyes) {
        for (int end = now + ticks; now < end; ) {
            now += STEP;
            motion.live(now);
            double[] e = eyes.apply(now);
            int at = now;
            motion.step(watch, now, e[0], e[1], e[2], LOOK, ANIMATE, (part, t, glide) -> keys.add(new Key(at, part, t.clone(), glide)));
        }
    }

    /** Eyes standing still this far from the NPC in the direction of yaw (0 = in front of it, south). */
    private static IntFunction<double[]> standing(double distance, double yaw) {
        double r = Math.toRadians(yaw);
        return tick -> new double[] {X - distance * Math.sin(r), EYES, Z + distance * Math.cos(r)};
    }

    private List<Key> keys(Part part, int from, int to) {
        return keys.stream().filter(k -> k.part == part && k.tick >= from && k.tick < to).toList();
    }

    /** The yaw a keyframe's part faces (the client's half turn and the right rotation cancel). */
    private static float facing(float[] t) {
        double[] forward = Turns.rotate(new double[] {t[3], t[4], t[5], t[6]}, Turns.FRONT);
        return Look.yaw(forward[0], forward[2]);
    }

    /** How high (pixels from the feet) the tip of an arm keyframe's hand is. */
    private static float hand(float[] t) {
        return (float) ((t[1] + Turns.rotate(new double[] {t[3], t[4], t[5], t[6]}, new double[] {0, -12 * K, 0})[1]) / K);
    }

    /** Where (blocks) the tip of an arm keyframe's hand is. */
    private static double[] tip(float[] t) {
        double[] v = Turns.rotate(new double[] {t[3], t[4], t[5], t[6]}, new double[] {0, -12 * K, 0});
        return new double[] {t[0] + v[0], t[1] + v[1], t[2] + v[2]};
    }

    /** A keyframe at start + i·KEY for every i whose glide ends by moving (ticks from start), and none off that grid. */
    private static void onTheGrid(List<Key> keys, int start, int moving) {
        List<Integer> ticks = keys.stream().map(Key::tick).toList();
        for (int t = 0; t + KEY <= moving; t += KEY) assertTrue(ticks.contains(start + t), "a keyframe at " + t + ": " + ticks);
        for (int t : ticks) assertEquals(0, (t - start) % KEY, "on the grid: " + ticks);
    }

    @Test
    void sendsNothingToViewersBeyondAnimateRadius() {
        run(600, standing(30, 0));
        assertTrue(keys.isEmpty());
    }

    @Test
    void restsOnceWhenTheViewerWalksAwayAndThenStaysQuiet() {
        run(100, standing(4, 20));
        assertFalse(keys.isEmpty());
        keys.clear();
        run(2, standing(30, 20));
        assertFalse(keys.isEmpty());
        for (Key k : keys) {
            assertEquals(Motion.REST_GLIDE, k.glide);
            assertArrayEquals(motion.rest(k.part), k.t, 1e-6f);
        }
        keys.clear();
        run(400, standing(30, 20));
        assertTrue(keys.isEmpty());
    }

    @Test
    void aRestingNpcCostsAFewSmallPacketsASecond() {
        // Out of look-radius but close enough to see it breathe and shift its weight.
        run(1200, standing(12, 0));
        double perSecond = keys.size() / 60.0;
        assertTrue(perSecond >= 2 && perSecond <= 9, perSecond + " keyframes a second");
        // Breathing moves head, body, arms and the held item together on the NPC's beat (a quarter
        // breath), each glide lasting a tick past the next keyframe; the legs only move when it shifts its weight.
        List<Integer> beat = keys(Part.HEAD, 0, now).stream().map(Key::tick).toList();
        for (Part part : new Part[] {Part.BODY, Part.RIGHT_ARM, Part.LEFT_ARM, Part.HELD}) {
            assertEquals(beat, keys(part, 0, now).stream().map(Key::tick).toList(), part.name());
        }
        List<Key> head = keys(Part.HEAD, 0, now);
        for (int i = 2; i < head.size(); i++) {
            assertEquals(0, (head.get(i).tick - head.get(i - 1).tick) % 16);
            assertEquals(17, head.get(i - 1).glide);
        }
        for (Key k : keys(Part.RIGHT_LEG, 0, now)) assertTrue(beat.contains(k.tick));
        assertTrue(keys(Part.RIGHT_LEG, 0, now).size() < head.size());
    }

    @Test
    void headAndThenBodyFollowSomeoneWalkingRoundIt() {
        // Once round at 3 blocks in 8 seconds, starting in front.
        run(160, tick -> {
            double r = Math.toRadians(360.0 * tick / 160);
            return new double[] {X - 3 * Math.sin(r), EYES, Z + 3 * Math.cos(r)};
        });
        // Behind it, the head alone could not look: the body turned well round on the way.
        float most = 0;
        for (Key k : keys(Part.BODY, 0, now)) most = Math.max(most, Look.apart(0, facing(k.t)));
        assertTrue(most > 100, "body turned " + most);
        // Standing still in front again, it ends up looking right at them.
        run(60, standing(3, 0));
        List<Key> head = keys(Part.HEAD, 0, now);
        assertTrue(Look.apart(0, facing(head.getLast().t)) < 3);
    }

    @Test
    void aWaveSendsTheRightArmEveryKeyTicksAndLiftsItBesideTheHead() {
        run(100, standing(4, 0));
        Animation wave = TestAnimations.preset("wave");
        assertTrue(watch.request(wave, Motion.Kind.COMMAND, now));
        int start = now + STEP;
        run(80, standing(4, 0));
        List<Key> arm = keys(Part.RIGHT_ARM, start, start + wave.length());
        // One keyframe every KEY ticks while the wave moves the arm (its last keyframe, back at rest, is at 44).
        onTheGrid(arm, start, 44);
        for (int i = 0; i < arm.size(); i++) {
            Key k = arm.get(i);
            assertEquals(KEY + 1, k.glide);           // the glide outlasts the gap to the next one
            // Raised (above the eyes) while it waves.
            int at = k.tick + KEY - start;
            if (at >= 8 && at <= 36) assertTrue(hand(k.t) > 28, "hand at " + hand(k.t) + " px, tick " + at);
        }
        // Back down by the end, and the left arm never waved.
        assertTrue(hand(arm.getLast().t) < 14);
        for (Key k : keys(Part.LEFT_ARM, start, start + wave.length())) assertTrue(hand(k.t) < 14);
        assertNull(watch.playing(now));
    }

    @Test
    void talkingNodsAndGesturesForTheClicker() {
        run(100, standing(3, 30));
        Animation talk = TestAnimations.preset("talk");
        watch.request(talk, Motion.Kind.CLICKED, now);
        int start = now + STEP;
        run(60, standing(3, 30));
        onTheGrid(keys(Part.RIGHT_ARM, start, start + talk.length()), start, 40);
        onTheGrid(keys(Part.HEAD, start, start + talk.length()), start, 36);     // its last nod ends at 36
        // Still looking at them while it talks.
        for (Key k : keys(Part.HEAD, start, start + talk.length())) assertTrue(Look.apart(30, facing(k.t)) < 3);
        // The hop at the start lifts every part.
        double top = keys(Part.LEFT_LEG, start, start + 8).stream().mapToDouble(k -> k.t[1]).max().orElse(0);
        assertEquals(motion.rest(Part.LEFT_LEG)[1] + 3 * K, top, 0.2 * K);
    }

    @Test
    void looksAroundOnlyForViewersItIsNotLookingAt() {
        run(100, standing(12, 0));
        Animation look = TestAnimations.preset("look_around");
        motion.share(look, Motion.Kind.IDLE, now);
        int start = now + STEP;
        run(60, standing(12, 0));
        float most = 0;
        for (Key k : keys(Part.HEAD, start, start + 60)) most = Math.max(most, Look.apart(0, facing(k.t)));
        assertTrue(most > 40, "turned " + most);

        // Someone it is looking at keeps its eyes.
        Motion.Watch near = motion.watch();
        List<Key> seen = new ArrayList<>();
        int at = now;
        for (int t = 0; t < 100; t += STEP) {
            int tick = at + t;
            if (t == 40) motion.share(look, Motion.Kind.IDLE, tick);
            motion.live(tick);
            double[] e = standing(3, 0).apply(tick);
            motion.step(near, tick, e[0], e[1], e[2], LOOK, ANIMATE, (part, tr, glide) -> seen.add(new Key(tick, part, tr.clone(), glide)));
        }
        for (Key k : seen) if (k.part == Part.HEAD && k.tick >= at + 40) assertTrue(Look.apart(0, facing(k.t)) < 3);

        // But a command (or a preview) of a glance animation plays even for them.
        assertTrue(near.request(look, Motion.Kind.COMMAND, at + 100));
        float turned = 0;
        for (int t = 100; t < 160; t += STEP) {
            int tick = at + t;
            motion.live(tick);
            double[] e = standing(3, 0).apply(tick);
            List<Key> out = new ArrayList<>();
            motion.step(near, tick, e[0], e[1], e[2], LOOK, ANIMATE, (part, tr, glide) -> out.add(new Key(tick, part, tr.clone(), glide)));
            for (Key k : out) if (k.part == Part.HEAD) turned = Math.max(turned, Look.apart(0, facing(k.t)));
        }
        assertTrue(turned > 40, "commanded look around turned " + turned);
    }

    @Test
    void theirOwnAnimationWinsOverTheSharedOne() {
        run(40, standing(12, 0));
        motion.share(TestAnimations.preset("stretch"), Motion.Kind.IDLE, now);
        watch.request(TestAnimations.preset("wave"), Motion.Kind.GREET, now);
        int start = now + STEP;
        run(30, standing(12, 0));
        // The wave: right arm up, the left arm (which the stretch would lift) stays down.
        assertTrue(keys(Part.RIGHT_ARM, start, start + 30).stream().anyMatch(k -> hand(k.t) > 28));
        assertTrue(keys(Part.LEFT_ARM, start, start + 30).stream().allMatch(k -> hand(k.t) < 14));
        assertEquals(Motion.Kind.IDLE, motion.sharing(now));
        // An idle never cuts short something played for everyone; an everyone one replaces an idle.
        assertTrue(motion.share(TestAnimations.preset("clap"), Motion.Kind.EVERYONE, now));
        assertFalse(motion.share(TestAnimations.preset("nod"), Motion.Kind.IDLE, now));
    }

    @Test
    void moreImportantAnimationsAreNotCutShort() {
        run(20, standing(4, 0));
        Animation wave = TestAnimations.looping(TestAnimations.preset("wave"));
        assertTrue(watch.request(wave, Motion.Kind.PREVIEW, now));
        assertFalse(watch.request(TestAnimations.preset("greet"), Motion.Kind.GREET, now), "a greeting waits for the preview");
        assertFalse(watch.request(TestAnimations.preset("talk"), Motion.Kind.CLICKED, now), "so does a click");
        run(200, standing(4, 0));
        // A looping preview goes round and round until stopped.
        assertEquals(Motion.Kind.PREVIEW, watch.playing(now));
        assertTrue(watch.request(TestAnimations.preset("nod"), Motion.Kind.PREVIEW, now), "a new preview replaces it");
        run(4, standing(4, 0));
        assertTrue(watch.stop(Motion.Kind.PREVIEW));
        assertNull(watch.playing(now));
        // A click replaces a greeting.
        assertTrue(watch.request(TestAnimations.preset("greet"), Motion.Kind.GREET, now));
        run(4, standing(4, 0));
        assertTrue(watch.request(TestAnimations.preset("talk"), Motion.Kind.CLICKED, now));
        assertFalse(watch.request(TestAnimations.preset("greet"), Motion.Kind.GREET, now));
    }

    @Test
    void stoppingOrSwitchingBlendsInsteadOfJumping() {
        run(40, standing(4, 0));
        watch.request(TestAnimations.looping(TestAnimations.preset("wave")), Motion.Kind.PREVIEW, now);
        run(20, standing(4, 0));                 // the arm is up, waving
        int stop = now;
        watch.stop(Motion.Kind.PREVIEW);
        run(30, standing(4, 0));
        List<Key> arm = keys(Part.RIGHT_ARM, stop - 4, now);
        double most = 0;
        for (int i = 1; i < arm.size(); i++) {
            double[] a = tip(arm.get(i - 1).t), b = tip(arm.get(i).t);
            most = Math.max(most, Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2)) / K);
        }
        // Without the blend the hand would drop 20 pixels in one keyframe; blended it takes several.
        assertTrue(most < 12, "the hand moved " + most + " px in one keyframe");
        assertTrue(arm.size() >= Motion.FADE / KEY, arm.size() + " keyframes");
        assertTrue(hand(arm.getLast().t) < 14, "and it ends at rest");
    }

    @Test
    void itLooksAtSomeoneItBeckonsWhateverLookRadiusSays() {
        run(20, standing(12, 60));
        List<Key> before = keys(Part.HEAD, 0, now);
        for (Key k : before) assertTrue(Look.apart(0, facing(k.t)) < 3, "not looking at someone 12 blocks off");
        watch.request(TestAnimations.preset("beckon"), Motion.Kind.BECKON, now);
        int start = now + STEP;
        run(30, standing(12, 60));
        assertTrue(watch.looking());
        Key last = keys(Part.HEAD, start, now).getLast();
        assertTrue(Look.apart(60, facing(last.t)) < 10, "looking at them: " + facing(last.t));
        run(40, standing(12, 60));
        assertFalse(watch.looking(), "and back to its own business after");
    }

    @Test
    void theHeldItemMovesWithTheLeftArm() {
        run(20, standing(12, 0));
        watch.request(TestAnimations.preset("clap"), Motion.Kind.COMMAND, now);
        int start = now + STEP;
        run(36, standing(12, 0));
        List<Key> held = keys(Part.HELD, start, start + 36), arm = keys(Part.LEFT_ARM, start, start + 36);
        assertEquals(arm.stream().map(Key::tick).toList(), held.stream().map(Key::tick).toList());
        assertTrue(held.size() >= 36 / KEY - 1);
    }

    @Test
    void longerKeyTicksSendHalfAsMany() {
        Motion slow = new Motion(X, RigPose.EYES * K, Z, 0f, SCALE, false, true, false, 60, 4, STEP, new Random(7));
        Motion.Watch w = slow.watch();
        List<Key> out = new ArrayList<>();
        int t = 0;
        w.request(TestAnimations.preset("wave"), Motion.Kind.COMMAND, t);
        int start = t + STEP;
        for (; t < 60; ) {
            t += STEP;
            slow.live(t);
            double[] e = standing(4, 0).apply(t);
            int at = t;
            slow.step(w, t, e[0], e[1], e[2], LOOK, ANIMATE, (part, tr, glide) -> out.add(new Key(at, part, tr.clone(), glide)));
        }
        List<Key> arm = out.stream().filter(k -> k.part == Part.RIGHT_ARM && k.tick >= start && k.tick < start + 48).toList();
        assertEquals(44 / 4, arm.size());
        for (int i = 0; i < arm.size(); i++) {
            assertEquals(start + 4 * i, arm.get(i).tick);
            assertEquals(5, arm.get(i).glide);
        }
    }
}
