package dev.livelynpcs.rig;

import dev.livelynpcs.anim.Channel;

import static dev.livelynpcs.rig.Turns.ACROSS;
import static dev.livelynpcs.rig.Turns.FRONT;
import static dev.livelynpcs.rig.Turns.UP;
import static dev.livelynpcs.rig.Turns.about;
import static dev.livelynpcs.rig.Turns.add;
import static dev.livelynpcs.rig.Turns.mul;
import static dev.livelynpcs.rig.Turns.rotate;

/**
 * One moment of an NPC model (rig): its inputs, and after {@link #solve()} where every part is. This
 * is DESIGN.md "Coordinates and the rig maths" line for line; the web editor's 3D view does the same
 * sums in JavaScript and src/test/resources/pose-fixtures.json holds answers both must give.
 *
 * <p>Units are rig pixels (a pixel is scale/16 blocks), origin at the NPC's feet, +y up. In the NPC's
 * own frame (before its yaw) it faces +z and its right is -x. Each part's origin and rotation are
 * given in that body frame; {@link #display} turns them into the item display's transformation.
 *
 * <p>Inputs: the animation's channel values (0 = rest), the NPC's yaw, where it looks (a body turn,
 * and a head yaw and pitch on top of it), how far into a breath it is (0..1), its weight shift (pixels,
 * hips towards its left) and whether the skin has slim arms. One instance is reused as scratch; it is
 * not thread safe (everything runs on the main thread).
 */
public final class RigPose {

    /** Breath at its deepest: shoulders and head rise (pixels), arms swing out and the chin lifts (degrees). */
    public static final double BREATH_RISE = 0.3, BREATH_ARMS = 2.5, BREATH_CHIN = 1.5;
    /** The head's reach from the body (degrees), animation and gaze together. */
    public static final double HEAD_YAW_LIMIT = 80, HEAD_PITCH_LIMIT = 60;
    /** Heights in pixels: the hips, the torso's length, a limb's length; the top of the head and the eyes. */
    public static final double HIP = 12, TORSO = 12, LIMB = 12, HEIGHT = 32, EYES = 28;
    /**
     * The right rotation of the six body-part displays: half a turn about y, undoing the half turn the
     * 26.3 client gives every item display's model (without it every NPC shows its back).
     */
    public static final float[] BODY_RIGHT_ROTATION = {0, 1, 0, 0};
    public static final float[] NO_TURN = {0, 0, 0, 1};

    private static final int PARTS = Part.values().length;

    // ---- inputs ----
    /** Channel values (Channel.COUNT long), 0 = rest. */
    public final float[] channels = new float[Channel.COUNT];
    /** The NPC's yaw (Minecraft: 0 faces south, +z; 90 faces west, -x). */
    public float yaw;
    /** Where it looks: gb, the body's turn from its yaw; gy and gp, the head's yaw and pitch on top. */
    public float gazeBody, gazeYaw, gazePitch;
    /** How far into a breath, 0..1. */
    public float breath;
    /** Weight shift: pixels the hips move towards its left (negative: its right); the feet stay put. */
    public float lean;
    /** The skin has slim (3 pixel) arms. */
    public boolean slim;

    // ---- results ----
    private final double[][] origin = new double[PARTS][], rotation = new double[PARTS][];
    private double stretch = 1;
    private double[] root = {0, 0, 0}, bodyTurn = {0, 0, 0, 1};

    /** Everything back to rest: channels 0, no gaze, breath or lean (yaw and slim stay). */
    public RigPose rest() {
        java.util.Arrays.fill(channels, 0f);
        gazeBody = gazeYaw = gazePitch = breath = lean = 0;
        return this;
    }

    private double ch(Channel c) {
        return channels[c.ordinal()];
    }

    /** Works out every part's origin and rotation from the inputs (DESIGN.md steps 1 to 3). */
    public RigPose solve() {
        // 1. The body's yaw and its turn.
        double psi = yaw + gazeBody + ch(Channel.ROOT_YAW);
        bodyTurn = about(UP, -psi);
        // 2. Root offset: to its right, up, forward.
        root = rotate(bodyTurn, new double[] {-ch(Channel.ROOT_X), ch(Channel.ROOT_Y), ch(Channel.ROOT_Z)});
        // 3. In the body frame. Lean: the hips shift, the legs tilt so the feet stay planted.
        double tau = Math.asin(Math.max(-1, Math.min(1, -lean / LIMB)));
        double drop = -LIMB * (1 - Math.cos(tau));
        double[] hips = {lean, HIP + drop, 0};
        double rise = BREATH_RISE * breath;
        double[] torso = mul(about(UP, -ch(Channel.TORSO_YAW)), about(ACROSS, ch(Channel.TORSO_PITCH)),
                about(FRONT, ch(Channel.TORSO_ROLL)));
        double[] neck = add(hips, rotate(torso, new double[] {0, TORSO + rise, 0}));
        set(Part.BODY, neck, torso);
        stretch = 1 + rise / TORSO;

        double hy = clamp(gazeYaw + ch(Channel.HEAD_YAW), -HEAD_YAW_LIMIT, HEAD_YAW_LIMIT);
        double hp = clamp(gazePitch + ch(Channel.HEAD_PITCH) - BREATH_CHIN * breath, -HEAD_PITCH_LIMIT, HEAD_PITCH_LIMIT);
        set(Part.HEAD, neck, mul(torso, about(UP, -hy), about(ACROSS, hp), about(FRONT, ch(Channel.HEAD_ROLL))));

        double e = slim ? 0.5 : 0;
        double shoulderY = 10 + rise + ch(Channel.SHOULDERS) - e;
        arm(Part.RIGHT_ARM, -1, ch(Channel.RIGHT_ARM_FORWARD), ch(Channel.RIGHT_ARM_OUT), ch(Channel.RIGHT_ARM_ACROSS),
                hips, torso, shoulderY, e);
        double[] left = arm(Part.LEFT_ARM, 1, ch(Channel.LEFT_ARM_FORWARD), ch(Channel.LEFT_ARM_OUT),
                ch(Channel.LEFT_ARM_ACROSS), hips, torso, shoulderY, e);

        double tilt = Math.toDegrees(tau);
        leg(Part.RIGHT_LEG, -1, ch(Channel.RIGHT_LEG_FORWARD), ch(Channel.RIGHT_LEG_OUT), tilt, hips[1]);
        leg(Part.LEFT_LEG, 1, ch(Channel.LEFT_LEG_FORWARD), ch(Channel.LEFT_LEG_OUT), tilt, hips[1]);

        // The held item: where the game draws an off-hand item on a player (ItemInHandLayer).
        double[] grip = add(hips, rotate(torso, new double[] {5 - e, shoulderY, 0}));
        set(Part.HELD, add(grip, rotate(left, new double[] {1, -10, 2})), mul(left, about(ACROSS, 90)));
        return this;
    }

    /** An arm (side s: -1 right, +1 left) about its shoulder pivot; returns its rotation in the body frame. */
    private double[] arm(Part part, int s, double forward, double out, double across, double[] hips, double[] torso,
                         double shoulderY, double e) {
        double[] own = mul(about(FRONT, s * (out + BREATH_ARMS * breath)), about(UP, -s * across), about(ACROSS, -forward));
        double[] turn = mul(torso, own);
        double[] pivot = add(hips, rotate(torso, new double[] {5 * s, shoulderY, 0}));
        set(part, add(pivot, rotate(turn, new double[] {s * (1 - e), 2, 0})), turn);
        return turn;
    }

    private void leg(Part part, int s, double forward, double out, double tilt, double hipY) {
        set(part, new double[] {2 * s + lean, hipY, 0}, mul(about(FRONT, tilt + s * out), about(ACROSS, -forward)));
    }

    private void set(Part part, double[] at, double[] turn) {
        origin[part.ordinal()] = at;
        rotation[part.ordinal()] = turn;
    }

    /** A part's origin in the body frame (pixels). */
    public double[] origin(Part part) {
        return origin[part.ordinal()].clone();
    }

    /** A part's rotation in the body frame. */
    public double[] rotation(Part part) {
        return rotation[part.ordinal()].clone();
    }

    /** The body's height stretch k as it breathes in (its scale along y). */
    public double stretch() {
        return stretch;
    }

    /** The root offset o: pixels from the feet, already turned with the body. */
    public double[] root() {
        return root.clone();
    }

    /** The body's turn Rb (its yaw, gaze and root yaw). */
    public double[] bodyTurn() {
        return bodyTurn.clone();
    }

    /**
     * A part's item display transformation (DESIGN.md step 4) into out (8 long): translation x, y, z in
     * blocks, left rotation x, y, z, w, and the scale along the part's height (the body stretches as it
     * breathes; every other scale is the rig's). The right rotation is fixed per part: see
     * {@link #rightRotation}.
     */
    public void display(Part part, float scale, float[] out) {
        double[] p = rotate(bodyTurn, origin[part.ordinal()]);
        double[] q = mul(bodyTurn, rotation[part.ordinal()]);
        double k = scale / 16.0;
        out[0] = (float) ((root[0] + p[0]) * k);
        out[1] = (float) ((root[1] + p[1]) * k);
        out[2] = (float) ((root[2] + p[2]) * k);
        out[3] = (float) q[0];
        out[4] = (float) q[1];
        out[5] = (float) q[2];
        out[6] = (float) q[3];
        out[7] = part == Part.BODY ? (float) (scale * stretch) : scale;
    }

    /** The fixed right rotation of a part's display: the half turn for body parts, none for the held item. */
    public static float[] rightRotation(Part part) {
        return part == Part.HELD ? NO_TURN.clone() : BODY_RIGHT_ROTATION.clone();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
