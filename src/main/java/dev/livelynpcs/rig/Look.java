package dev.livelynpcs.rig;

/**
 * The angles an NPC turns to when it looks at someone, in Minecraft's convention: yaw 0 faces south
 * (+z) and grows turning west (90 faces -x); pitch is negative looking up and positive looking down.
 */
public final class Look {

    private Look() {
    }

    /** The yaw that faces along (dx, dz). */
    public static float yaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** The pitch that looks along (dx, dy, dz). */
    public static float pitch(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    /** How far apart two yaws are, the short way round (0 to 180 degrees). */
    public static float apart(float a, float b) {
        return Math.abs(wrap(b - a));
    }

    /** An angle brought into -180..180, so a turn takes the short way round. */
    public static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d > 180f) d -= 360f;
        else if (d < -180f) d += 360f;
        return d;
    }
}
