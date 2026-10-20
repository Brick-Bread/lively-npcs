package dev.livelynpcs.anim;

import dev.livelynpcs.rig.Part;
import dev.livelynpcs.rig.RigPose;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arms must not pass through the head or the body. For every preset at every tick, with classic and
 * slim arms, breathing out and in, the arms' boxes are sampled (a point every quarter pixel, inside
 * and on the surface) and each point's depth inside the head's box and the torso's box is measured.
 * An arm may go at most 0.6 px into the head, at most 1.2 px into the torso near the shoulder (within
 * 4 px of the shoulder pivot, which is 2 px below the arm's top: the joint, where a real shoulder
 * overlaps too) and at most 0.6 px into the torso anywhere else.
 * Boxes are the base layers of DESIGN.md "Part boxes" (the hat and sleeves are not counted).
 */
class ArmClippingTest {

    static final double HEAD_LIMIT = 0.6, SHOULDER_LIMIT = 1.2, TORSO_LIMIT = 0.6;
    /** The top of an arm down to this many pixels below its top counts as the shoulder: 4 px past the pivot. */
    static final double SHOULDER = 6;
    private static final double STEP = 0.25;

    /** The deepest an arm goes into the head, into the torso at the shoulder, and into the torso elsewhere. */
    record Depths(double head, double shoulder, double torso) {
        boolean ok() {
            return head <= HEAD_LIMIT && shoulder <= SHOULDER_LIMIT && torso <= TORSO_LIMIT;
        }
    }

    @Test
    void presetsKeepTheArmsOutOfTheHeadAndBody() {
        AnimationStore store = new AnimationStore(java.nio.file.Path.of("no-server-folder"),
                name -> ArmClippingTest.class.getClassLoader().getResourceAsStream(name), Logger.getAnonymousLogger());
        store.reload();
        List<String> failures = new ArrayList<>();
        StringBuilder worst = new StringBuilder();
        float[] values = new float[Channel.COUNT];
        RigPose pose = new RigPose();
        for (String id : AnimationStore.PRESETS) {
            Animation a = store.preset(id);
            Depths most = new Depths(0, 0, 0);
            for (boolean slim : new boolean[] {false, true}) {
                for (float breath : new float[] {0, 1}) {
                    for (int tick = 0; tick <= a.length(); tick++) {
                        a.sample(tick, values);
                        pose.rest();
                        System.arraycopy(values, 0, pose.channels, 0, values.length);
                        pose.slim = slim;
                        pose.breath = breath;
                        pose.solve();
                        Depths d = depths(pose);
                        most = new Depths(Math.max(most.head, d.head), Math.max(most.shoulder, d.shoulder), Math.max(most.torso, d.torso));
                        if (!d.ok()) {
                            failures.add(String.format(Locale.ROOT, "%s tick %d %s breath %.0f: head %.2f, shoulder %.2f, torso %.2f",
                                    id, tick, slim ? "slim" : "classic", breath, d.head, d.shoulder, d.torso));
                        }
                    }
                }
            }
            worst.append(String.format(Locale.ROOT, "%n  %-12s head %.2f  shoulder %.2f  torso %.2f", id, most.head, most.shoulder, most.torso));
        }
        System.out.println("Deepest arm clipping per preset (px):" + worst);
        assertTrue(failures.isEmpty(), "arms clip:\n" + String.join("\n", failures.subList(0, Math.min(40, failures.size()))));
    }

    /**
     * The presets that put a hand up to the face really do: while the pose is held, the right hand (the
     * last 3 px of the arm) comes within NEAR px of the head, so the test above proves "near the face
     * without going into it", not "kept well away". Classic and slim, breathing out and in.
     */
    @Test
    void handsUpToTheFaceComeCloseWithoutGoingIn() {
        AnimationStore store = new AnimationStore(java.nio.file.Path.of("no-server-folder"),
                name -> ArmClippingTest.class.getClassLoader().getResourceAsStream(name), Logger.getAnonymousLogger());
        store.reload();
        // Preset and a tick while the hand is held at the face.
        for (Object[] held : new Object[][] {{"salute", 16}}) {
            String id = (String) held[0];
            int tick = (Integer) held[1];
            float[] values = new float[Channel.COUNT];
            store.preset(id).sample(tick, values);
            for (boolean slim : new boolean[] {false, true}) {
                for (float breath : new float[] {0, 1}) {
                    RigPose pose = new RigPose().rest();
                    System.arraycopy(values, 0, pose.channels, 0, values.length);
                    pose.slim = slim;
                    pose.breath = breath;
                    pose.solve();
                    double gap = gap(pose, Part.RIGHT_ARM);
                    assertTrue(gap <= NEAR, id + " hand " + gap + " px from the head (" + (slim ? "slim" : "classic") + ", breath " + breath + ")");
                    assertTrue(depths(pose).head() <= HEAD_LIMIT, id + " hand in the head");
                }
            }
        }
    }

    /** A hand counts as up to the face within this many pixels of the head. */
    static final double NEAR = 1.5;

    /** The shortest distance from the hand (the last 3 px of the arm) to the head's box: 0 touching or inside. */
    static double gap(RigPose p, Part arm) {
        double[] head = frame(p, Part.HEAD), f = frame(p, arm);
        double half = p.slim ? 1.5 : 2, best = Double.MAX_VALUE;
        for (double x = -half; x <= half + 1e-9; x += STEP) {
            for (double y = -12; y <= -9 + 1e-9; y += STEP) {
                for (double z = -2; z <= 2 + 1e-9; z += STEP) {
                    double[] h = toLocal(head, toBody(f, x, y, z));
                    double dx = Math.max(Math.max(-4 - h[0], 0), h[0] - 4), dy = Math.max(Math.max(-h[1], 0), h[1] - 8),
                            dz = Math.max(Math.max(-4 - h[2], 0), h[2] - 4);
                    best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
        }
        return best;
    }

    /** How deep the arms go into the head and torso in this (solved) pose. */
    static Depths depths(RigPose p) {
        double[] head = frame(p, Part.HEAD), body = frame(p, Part.BODY);
        double bodyBottom = -12 * p.stretch();
        double half = p.slim ? 1.5 : 2;
        double inHead = 0, inShoulder = 0, inTorso = 0;
        for (Part arm : new Part[] {Part.RIGHT_ARM, Part.LEFT_ARM}) {
            double[] f = frame(p, arm);
            for (double x = -half; x <= half + 1e-9; x += STEP) {
                for (double y = -12; y <= 1e-9; y += STEP) {
                    for (double z = -2; z <= 2 + 1e-9; z += STEP) {
                        double[] q = toBody(f, x, y, z);
                        double[] h = toLocal(head, q);
                        inHead = Math.max(inHead, depth(h, -4, 4, 0, 8, -4, 4));
                        double[] b = toLocal(body, q);
                        double t = depth(b, -4, 4, bodyBottom, 0, -2, 2);
                        if (y >= -SHOULDER) inShoulder = Math.max(inShoulder, t);
                        else inTorso = Math.max(inTorso, t);
                    }
                }
            }
        }
        return new Depths(inHead, inShoulder, inTorso);
    }

    /** A part's frame as origin (3) then its rotation matrix, row by row (9). */
    private static double[] frame(RigPose p, Part part) {
        double[] o = p.origin(part), q = p.rotation(part);
        double x = q[0], y = q[1], z = q[2], w = q[3];
        return new double[] {o[0], o[1], o[2],
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
    }

    private static double[] toBody(double[] f, double x, double y, double z) {
        return new double[] {f[0] + f[3] * x + f[4] * y + f[5] * z, f[1] + f[6] * x + f[7] * y + f[8] * z,
                f[2] + f[9] * x + f[10] * y + f[11] * z};
    }

    /** A body-frame point in the part's own frame (the transpose turns back). */
    private static double[] toLocal(double[] f, double[] q) {
        double dx = q[0] - f[0], dy = q[1] - f[1], dz = q[2] - f[2];
        return new double[] {f[3] * dx + f[6] * dy + f[9] * dz, f[4] * dx + f[7] * dy + f[10] * dz, f[5] * dx + f[8] * dy + f[11] * dz};
    }

    /** How far inside the box a point is (0 outside or on its surface). */
    private static double depth(double[] v, double x0, double x1, double y0, double y1, double z0, double z1) {
        double d = Math.min(Math.min(v[0] - x0, x1 - v[0]), Math.min(Math.min(v[1] - y0, y1 - v[1]), Math.min(v[2] - z0, z1 - v[2])));
        return Math.max(0, d);
    }
}
