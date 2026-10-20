package dev.livelynpcs.rig;

/**
 * Where an NPC model looks for one viewer; each viewer sees it looking at them. Its head turns to them
 * within a person's reach (HEAD_YAW either side of the body, PITCH_UP up and PITCH_DOWN down), and when
 * they go further round than the head can follow, the body turns after them, at most TURN_PER_TICK a
 * tick, until the head is comfortable again (within COMFORT of straight ahead): a quick glance stays a
 * glance, but someone walking round it gets the whole NPC turning. With nobody to look at, head and
 * body go back to the NPC's own yaw. Angles are degrees, Minecraft's way round (Look).
 */
public final class Gaze {

    /** The head's reach from the body: yaw either side, pitch up (negative) and down. */
    public static final float HEAD_YAW = 70, PITCH_UP = -40, PITCH_DOWN = 50;
    /** Once the body turns, it turns until the head is within this of straight ahead. */
    public static final float COMFORT = 25;
    /** The body turns at most this much a tick: 90 degrees a second. */
    public static final float TURN_PER_TICK = 4.5f;

    private final float rest, turn;
    private float body, head, pitch;
    private boolean looking, turning;

    /** A gaze resting at the NPC's yaw, stepped every stepTicks ticks. */
    public Gaze(float rest, int stepTicks) {
        this.rest = rest;
        this.turn = TURN_PER_TICK * stepTicks;
        this.body = rest;
    }

    /** One step: towards someone at (yaw, pitch) while looking at them, or back to rest when not. */
    public void step(boolean looking, float yaw, float pitch) {
        this.looking = looking;
        float want = looking ? yaw : rest;
        float off = Look.wrap(want - body);
        // Out of the head's reach the body starts turning; resting, it always goes all the way back.
        if (!looking || Math.abs(off) > HEAD_YAW) turning = true;
        if (turning) {
            float excess = Math.abs(off) - (looking ? COMFORT : 0);
            if (excess <= 0) {
                turning = false;
            } else {
                body = Look.wrap(body + Math.copySign(Math.min(excess, turn), off));
                off = Look.wrap(want - body);
            }
        }
        head = clamp(off, -HEAD_YAW, HEAD_YAW);
        this.pitch = looking ? clamp(pitch, PITCH_UP, PITCH_DOWN) : 0;
    }

    /** Back to the NPC's own yaw at once, as a freshly drawn model stands. */
    public void reset() {
        body = rest;
        head = 0;
        pitch = 0;
        looking = false;
        turning = false;
    }

    /** The body's yaw. */
    public float body() {
        return body;
    }

    /** The body's turn from the NPC's own yaw (gb). */
    public float bodyTurn() {
        return Look.wrap(body - rest);
    }

    /** The head's yaw from the body (gy). */
    public float head() {
        return head;
    }

    /** The head's pitch (gp). */
    public float pitch() {
        return pitch;
    }

    /** Whether it is looking at the viewer. */
    public boolean looking() {
        return looking;
    }

    static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
