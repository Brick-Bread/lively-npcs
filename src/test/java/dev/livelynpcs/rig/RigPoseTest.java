package dev.livelynpcs.rig;

import dev.livelynpcs.anim.Channel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rig maths of DESIGN.md: joints, which way things turn, and what the 26.3 client draws. */
class RigPoseTest {

    private static final double EPS = 1e-6;
    private static final float SCALE = 0.9375f, K = SCALE / 16;

    private static RigPose pose() {
        return new RigPose();
    }

    private static RigPose set(RigPose p, Channel c, float v) {
        p.channels[c.ordinal()] = v;
        return p;
    }

    /** Where a point of a part (pixels in its own frame) is, in the body frame. */
    private static double[] at(RigPose p, Part part, double... local) {
        return Turns.add(p.origin(part), Turns.rotate(p.rotation(part), local));
    }

    /** A point in the body frame, in a part's own frame. */
    private static double[] local(RigPose p, Part part, double[] body) {
        return Turns.rotate(Turns.inverse(p.rotation(part)), Turns.sub(body, p.origin(part)));
    }

    /** The direction a part's own +z (its front) points, in the body frame. */
    private static double[] front(RigPose p, Part part) {
        return Turns.rotate(p.rotation(part), Turns.FRONT);
    }

    /** The direction a hanging limb (its own -y) points, in the body frame. */
    private static double[] down(RigPose p, Part part) {
        return Turns.rotate(p.rotation(part), new double[] {0, -1, 0});
    }

    @Test
    void jointsAtRest() {
        RigPose p = pose().solve();
        assertArrayEquals(new double[] {0, 24, 0}, p.origin(Part.HEAD), EPS);
        assertArrayEquals(new double[] {0, 24, 0}, p.origin(Part.BODY), EPS);
        assertArrayEquals(new double[] {-6, 24, 0}, p.origin(Part.RIGHT_ARM), EPS);
        assertArrayEquals(new double[] {6, 24, 0}, p.origin(Part.LEFT_ARM), EPS);
        assertArrayEquals(new double[] {-2, 12, 0}, p.origin(Part.RIGHT_LEG), EPS);
        assertArrayEquals(new double[] {2, 12, 0}, p.origin(Part.LEFT_LEG), EPS);
        for (Part part : Part.BODY_PARTS) assertArrayEquals(new double[] {0, 0, 0, 1}, p.rotation(part), EPS, part.name());
        assertEquals(1, p.stretch(), EPS);
        // Arms hang 12 pixels from the top of the shoulder; legs reach the ground.
        assertArrayEquals(new double[] {-6, 12, 0}, at(p, Part.RIGHT_ARM, 0, -12, 0), EPS);
        assertArrayEquals(new double[] {2, 0, 0}, at(p, Part.LEFT_LEG, 0, -12, 0), EPS);
    }

    @Test
    void slimArmsAreHalfAPixelLowerAndCloser() {
        RigPose p = pose();
        p.slim = true;
        p.solve();
        assertArrayEquals(new double[] {-5.5, 23.5, 0}, p.origin(Part.RIGHT_ARM), EPS);
        assertArrayEquals(new double[] {5.5, 23.5, 0}, p.origin(Part.LEFT_ARM), EPS);
        assertArrayEquals(new double[] {5.5, 11.5, 2}, p.origin(Part.HELD), EPS);
    }

    @Test
    void armsStayAttachedAtTheVanillaShoulderPivot() {
        Random random = new Random(3);
        for (boolean slim : new boolean[] {false, true}) {
            double e = slim ? 0.5 : 0;
            for (int i = 0; i < 200; i++) {
                RigPose p = pose();
                p.slim = slim;
                p.breath = random.nextFloat();
                for (Channel c : new Channel[] {Channel.RIGHT_ARM_FORWARD, Channel.RIGHT_ARM_OUT, Channel.RIGHT_ARM_ACROSS,
                        Channel.LEFT_ARM_FORWARD, Channel.LEFT_ARM_OUT, Channel.LEFT_ARM_ACROSS}) {
                    set(p, c, random.nextFloat() * 300 - 90);
                }
                set(p, Channel.SHOULDERS, random.nextFloat() * 3);
                p.solve();
                double y = 22 + RigPose.BREATH_RISE * p.breath + p.channels[Channel.SHOULDERS.ordinal()] - e;
                // The pivot is 1 pixel in from the arm's outer half and 2 below its top, whatever the turn.
                assertArrayEquals(new double[] {-5, y, 0}, at(p, Part.RIGHT_ARM, 1 - e, -2, 0), 1e-9);
                assertArrayEquals(new double[] {5, y, 0}, at(p, Part.LEFT_ARM, -(1 - e), -2, 0), 1e-9);
            }
        }
    }

    @Test
    void headTurnsToItsRightLooksDownAndTiltsToItsRight() {
        // Facing +z, its right is -x.
        assertArrayEquals(new double[] {-Math.sin(Math.toRadians(30)), 0, Math.cos(Math.toRadians(30))},
                front(set(pose(), Channel.HEAD_YAW, 30).solve(), Part.HEAD), EPS);
        assertArrayEquals(new double[] {0, -Math.sin(Math.toRadians(20)), Math.cos(Math.toRadians(20))},
                front(set(pose(), Channel.HEAD_PITCH, 20).solve(), Part.HEAD), EPS);
        double[] top = Turns.rotate(set(pose(), Channel.HEAD_ROLL, 15).solve().rotation(Part.HEAD), Turns.UP);
        assertTrue(top[0] < -0.2, "top tilts to its right");
        // The gaze adds to the channels, and the sum stays within the head's reach.
        RigPose p = pose();
        p.gazeYaw = 70;
        set(p, Channel.HEAD_YAW, 30).solve();
        assertArrayEquals(new double[] {-Math.sin(Math.toRadians(80)), 0, Math.cos(Math.toRadians(80))}, front(p, Part.HEAD), EPS);
        p = pose();
        p.gazePitch = 50;
        set(p, Channel.HEAD_PITCH, 30).solve();
        assertArrayEquals(new double[] {0, -Math.sin(Math.toRadians(60)), Math.cos(Math.toRadians(60))}, front(p, Part.HEAD), EPS);
    }

    @Test
    void armsSwingForwardOutAndAcross() {
        assertArrayEquals(new double[] {0, 0, 1}, down(set(pose(), Channel.RIGHT_ARM_FORWARD, 90).solve(), Part.RIGHT_ARM), EPS);
        assertArrayEquals(new double[] {0, 1, 0}, down(set(pose(), Channel.LEFT_ARM_FORWARD, 180).solve(), Part.LEFT_ARM), EPS);
        // Out: each to its own side.
        assertArrayEquals(new double[] {-1, 0, 0}, down(set(pose(), Channel.RIGHT_ARM_OUT, 90).solve(), Part.RIGHT_ARM), EPS);
        assertArrayEquals(new double[] {1, 0, 0}, down(set(pose(), Channel.LEFT_ARM_OUT, 90).solve(), Part.LEFT_ARM), EPS);
        // Raised above the shoulder, positive out brings the hand in over the head.
        RigPose up = set(set(pose(), Channel.RIGHT_ARM_FORWARD, 170), Channel.RIGHT_ARM_OUT, 20).solve();
        assertTrue(down(up, Part.RIGHT_ARM)[0] > 0.2, "in over the head");
        // Across: pointing ahead, each arm swings in towards the chest.
        RigPose across = set(set(pose(), Channel.RIGHT_ARM_FORWARD, 90), Channel.RIGHT_ARM_ACROSS, 30).solve();
        assertArrayEquals(new double[] {0.5, 0, Math.cos(Math.toRadians(30))}, down(across, Part.RIGHT_ARM), EPS);
        across = set(set(pose(), Channel.LEFT_ARM_FORWARD, 90), Channel.LEFT_ARM_ACROSS, 30).solve();
        assertArrayEquals(new double[] {-0.5, 0, Math.cos(Math.toRadians(30))}, down(across, Part.LEFT_ARM), EPS);
    }

    @Test
    void legsSwingForwardAndOut() {
        assertArrayEquals(new double[] {0, -Math.cos(Math.toRadians(30)), 0.5},
                down(set(pose(), Channel.RIGHT_LEG_FORWARD, 30).solve(), Part.RIGHT_LEG), EPS);
        assertTrue(down(set(pose(), Channel.RIGHT_LEG_OUT, 20).solve(), Part.RIGHT_LEG)[0] < -0.3);
        assertTrue(down(set(pose(), Channel.LEFT_LEG_OUT, 20).solve(), Part.LEFT_LEG)[0] > 0.3);
    }

    @Test
    void torsoBendsAtTheHipsCarryingHeadAndArms() {
        RigPose rest = pose().solve();
        for (Channel c : new Channel[] {Channel.TORSO_PITCH, Channel.TORSO_ROLL, Channel.TORSO_YAW}) {
            RigPose bent = set(set(pose(), c, 30), Channel.RIGHT_ARM_FORWARD, 50).solve();
            RigPose straight = set(pose(), Channel.RIGHT_ARM_FORWARD, 50).solve();
            double[] hips = {0, 12, 0};
            double[] torso = bent.rotation(Part.BODY);
            for (Part part : new Part[] {Part.HEAD, Part.BODY, Part.RIGHT_ARM, Part.LEFT_ARM, Part.HELD}) {
                // Seen from the torso, nothing moved.
                double[] seen = Turns.rotate(Turns.inverse(torso), Turns.sub(bent.origin(part), hips));
                assertArrayEquals(Turns.sub(straight.origin(part), hips), seen, 1e-9, c + " " + part);
                assertEquals(0, Turns.apart(Turns.mul(torso, straight.rotation(part)), bent.rotation(part)), 1e-4, c + " " + part);
            }
            // The legs stay where they were.
            assertArrayEquals(rest.origin(Part.LEFT_LEG), bent.origin(Part.LEFT_LEG), EPS);
            assertArrayEquals(rest.rotation(Part.LEFT_LEG), bent.rotation(Part.LEFT_LEG), EPS);
        }
        // Bending forward puts the neck in front of the hips and lower.
        double[] neck = set(pose(), Channel.TORSO_PITCH, 30).solve().origin(Part.HEAD);
        assertArrayEquals(new double[] {0, 12 + 12 * Math.cos(Math.toRadians(30)), 12 * Math.sin(Math.toRadians(30))}, neck, EPS);
        // Rolling to its right puts the neck to its right (-x).
        assertTrue(set(pose(), Channel.TORSO_ROLL, 20).solve().origin(Part.HEAD)[0] < -3);
    }

    @Test
    void heldItemIsInTheLeftHand() {
        Random random = new Random(11);
        for (int i = 0; i < 200; i++) {
            RigPose p = pose();
            set(p, Channel.LEFT_ARM_FORWARD, random.nextFloat() * 220 - 60);
            set(p, Channel.LEFT_ARM_OUT, random.nextFloat() * 120 - 30);
            set(p, Channel.LEFT_ARM_ACROSS, random.nextFloat() * 90 - 30);
            set(p, Channel.TORSO_PITCH, random.nextFloat() * 40 - 10);
            p.breath = random.nextFloat();
            p.solve();
            // Classic arms: the bottom of the hand, on its front face, turned with the arm.
            assertArrayEquals(new double[] {0, -12, 2}, local(p, Part.LEFT_ARM, p.origin(Part.HELD)), 1e-9);
            assertEquals(0, Turns.apart(Turns.mul(p.rotation(Part.LEFT_ARM), Turns.about(Turns.ACROSS, 90)),
                    p.rotation(Part.HELD)), 1e-4);
            // Slim arms: the game draws the item half a pixel in, so it is within a pixel of the same spot.
            p.slim = true;
            p.solve();
            double[] hand = local(p, Part.LEFT_ARM, p.origin(Part.HELD));
            assertTrue(Math.abs(hand[0]) <= 1 + 1e-9 && Math.abs(hand[1] + 12) <= 1 + 1e-9 && Math.abs(hand[2] - 2) <= 1 + 1e-9,
                    java.util.Arrays.toString(hand));
        }
    }

    @Test
    void leaningKeepsTheFeetPlanted() {
        for (float lean : new float[] {-0.5f, 0.5f, 3}) {
            RigPose p = pose();
            p.lean = lean;
            p.solve();
            assertArrayEquals(new double[] {-2, 0, 0}, at(p, Part.RIGHT_LEG, 0, -12, 0), 1e-9);
            assertArrayEquals(new double[] {2, 0, 0}, at(p, Part.LEFT_LEG, 0, -12, 0), 1e-9);
            assertEquals(lean, p.origin(Part.HEAD)[0], 1e-9);
            assertTrue(p.origin(Part.HEAD)[1] <= 24 + 1e-9, "the hips drop a touch");
        }
    }

    @Test
    void breathingLiftsTheShouldersAndTheBodyStretchesToTheHips() {
        RigPose p = pose();
        p.breath = 1;
        p.solve();
        assertEquals(24.3, p.origin(Part.HEAD)[1], EPS);
        assertEquals(24.3, p.origin(Part.RIGHT_ARM)[1], 0.05);   // the arm swings out a touch too
        assertEquals(12, p.origin(Part.RIGHT_LEG)[1], EPS);
        // The body's bottom (12 pixels down, stretched) stays on the hips.
        assertEquals(12, p.origin(Part.BODY)[1] - 12 * p.stretch(), EPS);
        // Arms swing out a little, the chin lifts.
        assertTrue(down(p, Part.RIGHT_ARM)[0] < 0 && down(p, Part.LEFT_ARM)[0] > 0);
        assertTrue(front(p, Part.HEAD)[1] > 0);
    }

    @Test
    void rootMovesTheWholeNpc() {
        float[] t = new float[8];
        RigPose p = set(set(set(pose(), Channel.ROOT_X, 2), Channel.ROOT_Y, 3), Channel.ROOT_Z, 4).solve();
        // To its right (-x when facing south), up, and forward (+z).
        assertArrayEquals(new double[] {-2, 3, 4}, p.root(), EPS);
        p.display(Part.HEAD, SCALE, t);
        assertArrayEquals(new float[] {-2 * K, 27 * K, 4 * K}, new float[] {t[0], t[1], t[2]}, 1e-6f);
        // The same step facing west (yaw 90): its right is north (-z), forward is west (-x).
        p.yaw = 90;
        p.solve();
        assertArrayEquals(new double[] {-4, 3, -2}, p.root(), EPS);
        // A root yaw of 90 spins it to its right: facing south it then faces west.
        p = set(pose(), Channel.ROOT_YAW, 90).solve();
        assertArrayEquals(new double[] {-1, 0, 0}, Turns.rotate(Turns.mul(p.bodyTurn(), p.rotation(Part.HEAD)), Turns.FRONT), EPS);
    }

    @Test
    void displayTranslationTurnsWithTheBodyAndScales() {
        float[] t = new float[8];
        RigPose p = pose();
        p.yaw = 90;
        p.breath = 1;
        p.solve();
        p.display(Part.RIGHT_ARM, SCALE, t);
        // Facing west its right is north (-z).
        double[] arm = p.origin(Part.RIGHT_ARM);
        assertArrayEquals(new float[] {(float) (-arm[2] * K), (float) (arm[1] * K), (float) (arm[0] * K)},
                new float[] {t[0], t[1], t[2]}, 1e-6f);
        assertEquals(SCALE, t[7], 1e-6f);
        p.display(Part.BODY, SCALE, t);
        assertEquals(SCALE * p.stretch(), t[7], 1e-6f);
    }

    /**
     * What the 26.3 client draws: an item display's model point v (pixels of the pack's model, whose
     * point (8,8,8) is the part's origin) at translation + left·(scale·(right·(halfTurn·v))), the half
     * turn being the client's own about y. With the body parts' right rotation the face is in front
     * and the right arm on the NPC's right, whichever way it faces.
     */
    @Test
    void faceIsInFrontAndTheRightArmOnTheRightThroughTheClientsHalfTurn() {
        float[] t = new float[8];
        for (float yaw : new float[] {0, 90, -135, 180, 33}) {
            double r = Math.toRadians(yaw);
            double[] facing = {-Math.sin(r), 0, Math.cos(r)};
            double[] right = {-Math.cos(r), 0, -Math.sin(r)};
            RigPose p = pose();
            p.yaw = yaw;
            p.solve();
            p.display(Part.HEAD, SCALE, t);
            // The middle of the face: 4 up from the neck, on the head's front (+z in the pack's model).
            double[] face = onScreen(t, Part.HEAD, 0, 4, 4);
            assertArrayEquals(new double[] {t[0] + 4 * K * facing[0], t[1] + 4 * K, t[2] + 4 * K * facing[2]}, face, 1e-6, "yaw " + yaw);
            p.display(Part.RIGHT_ARM, SCALE, t);
            double[] outer = onScreen(t, Part.RIGHT_ARM, -2, -6, 0);
            assertArrayEquals(new double[] {t[0] + 2 * K * right[0], t[1] - 6 * K, t[2] + 2 * K * right[2]}, outer, 1e-6, "yaw " + yaw);
        }
    }

    private static double[] onScreen(float[] t, Part part, double... model) {
        double[] v = Turns.rotate(Turns.about(Turns.UP, 180), model);
        float[] right = RigPose.rightRotation(part);
        v = Turns.rotate(new double[] {right[0], right[1], right[2], right[3]}, v);
        double sy = part == Part.BODY ? t[7] : SCALE;
        v = Turns.rotate(new double[] {t[3], t[4], t[5], t[6]}, new double[] {v[0] * SCALE / 16, v[1] * sy / 16, v[2] * SCALE / 16});
        return new double[] {t[0] + v[0], t[1] + v[1], t[2] + v[2]};
    }
}
