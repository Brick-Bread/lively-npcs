package dev.livelynpcs.anim;

import dev.livelynpcs.rig.Part;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * One value an animation keyframe can set. DESIGN.md ("Rig maths") says exactly how each moves the
 * model; the editor's 3D view must follow the same rules. Angles are degrees, offsets pixels (1/16
 * block at scale 1). All zero is the rest pose: standing straight, arms down, facing ahead.
 *
 * <p>{@link #key} is the name in animation files and the editor's JSON ("right-arm-forward"); {@link
 * #min}/{@link #max} are the editor's slider range (files may hold anything; the runtime clamps
 * nothing but the head).
 */
public enum Channel {
    /** Whole NPC: steps to its right, rises (a hop), steps forward (pixels); spins to its right about its feet. */
    ROOT_X(true, -32, 32, Part.values()),
    ROOT_Y(true, -16, 32, Part.values()),
    ROOT_Z(true, -32, 32, Part.values()),
    ROOT_YAW(false, -360, 360, Part.values()),
    /** Torso bending at the hips: forward, to its right, twisting to its right. Head and arms go with it. */
    TORSO_PITCH(false, -45, 90, Part.HEAD, Part.BODY, Part.RIGHT_ARM, Part.LEFT_ARM, Part.HELD),
    TORSO_ROLL(false, -45, 45, Part.HEAD, Part.BODY, Part.RIGHT_ARM, Part.LEFT_ARM, Part.HELD),
    TORSO_YAW(false, -60, 60, Part.HEAD, Part.BODY, Part.RIGHT_ARM, Part.LEFT_ARM, Part.HELD),
    /** Head, on top of where it looks: turn to its right, look down, tilt its top to its right. */
    HEAD_YAW(false, -80, 80, Part.HEAD),
    HEAD_PITCH(false, -60, 60, Part.HEAD),
    HEAD_ROLL(false, -40, 40, Part.HEAD),
    /** Both shoulders lift (a shrug), pixels. */
    SHOULDERS(true, -2, 3, Part.RIGHT_ARM, Part.LEFT_ARM, Part.HELD),
    /** Right arm about the shoulder: forward (up in front), out to its side, across its chest. */
    RIGHT_ARM_FORWARD(false, -90, 220, Part.RIGHT_ARM),
    RIGHT_ARM_OUT(false, -60, 180, Part.RIGHT_ARM),
    RIGHT_ARM_ACROSS(false, -60, 90, Part.RIGHT_ARM),
    LEFT_ARM_FORWARD(false, -90, 220, Part.LEFT_ARM, Part.HELD),
    LEFT_ARM_OUT(false, -60, 180, Part.LEFT_ARM, Part.HELD),
    LEFT_ARM_ACROSS(false, -60, 90, Part.LEFT_ARM, Part.HELD),
    /** Legs about the hip: forward, out to its side. */
    RIGHT_LEG_FORWARD(false, -90, 90, Part.RIGHT_LEG),
    RIGHT_LEG_OUT(false, -30, 60, Part.RIGHT_LEG),
    LEFT_LEG_FORWARD(false, -90, 90, Part.LEFT_LEG),
    LEFT_LEG_OUT(false, -30, 60, Part.LEFT_LEG);

    public static final Channel[] ALL = values();
    public static final int COUNT = ALL.length;

    /** The name in animation files and JSON, e.g. "right-arm-forward". */
    public final String key;
    /** Pixels (offsets) rather than degrees. */
    public final boolean pixels;
    public final float min, max;
    private final Set<Part> moves;

    Channel(boolean pixels, float min, float max, Part... moves) {
        this.key = name().toLowerCase(Locale.ROOT).replace('_', '-');
        this.pixels = pixels;
        this.min = min;
        this.max = max;
        this.moves = moves.length == 0 ? EnumSet.noneOf(Part.class) : EnumSet.of(moves[0], moves);
    }

    /** Whether a value in this channel moves the part. */
    public boolean moves(Part part) {
        return moves.contains(part);
    }

    /** The channel with this file/JSON key, or null. */
    public static Channel byKey(String key) {
        for (Channel c : ALL) if (c.key.equals(key)) return c;
        return null;
    }
}
