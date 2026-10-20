package dev.livelynpcs.rig;

/**
 * Quaternion and vector sums for the rig, the way JOML (and so Minecraft) does them: a quaternion is
 * {x, y, z, w}, turns are right-handed, and {@code mul(a, b)} turns by b first, then by a. Angles in
 * the public methods are degrees. Kept free of server classes so the maths can be tested.
 */
public final class Turns {

    /** The rig's axes: up, across (to the NPC's left when it faces +z) and to the front. */
    public static final double[] UP = {0, 1, 0}, ACROSS = {1, 0, 0}, FRONT = {0, 0, 1};

    private Turns() {
    }

    /** Turning by degrees about a unit axis (right-handed). */
    public static double[] about(double[] axis, double degrees) {
        double half = Math.toRadians(degrees) / 2, s = Math.sin(half);
        return new double[] {axis[0] * s, axis[1] * s, axis[2] * s, Math.cos(half)};
    }

    /** The product a·b: turning by b, then by a. */
    public static double[] mul(double[] a, double[] b) {
        return new double[] {
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    /** The product of several turns, a·b·c...: the last one turns first. */
    public static double[] mul(double[] a, double[] b, double[]... more) {
        double[] q = mul(a, b);
        for (double[] m : more) q = mul(q, m);
        return q;
    }

    /** The point v turned by q. */
    public static double[] rotate(double[] q, double[] v) {
        double tx = 2 * (q[1] * v[2] - q[2] * v[1]), ty = 2 * (q[2] * v[0] - q[0] * v[2]), tz = 2 * (q[0] * v[1] - q[1] * v[0]);
        return new double[] {v[0] + q[3] * tx + q[1] * tz - q[2] * ty, v[1] + q[3] * ty + q[2] * tx - q[0] * tz,
                v[2] + q[3] * tz + q[0] * ty - q[1] * tx};
    }

    /** The opposite turn of a unit quaternion. */
    public static double[] inverse(double[] q) {
        return new double[] {-q[0], -q[1], -q[2], q[3]};
    }

    public static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    public static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    /** The same turn with w at least 0 (q and -q turn alike; this picks one way of writing it). */
    public static double[] canonical(double[] q) {
        return q[3] < 0 || (q[3] == 0 && (q[0] < 0 || (q[0] == 0 && (q[1] < 0 || (q[1] == 0 && q[2] < 0)))))
                ? new double[] {-q[0], -q[1], -q[2], -q[3]} : q.clone();
    }

    /** How far apart two turns are, in degrees (0 when they turn alike, whichever sign each is written with). */
    public static double apart(double[] a, double[] b) {
        double dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
        return Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
    }
}
