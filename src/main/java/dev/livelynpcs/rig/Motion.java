package dev.livelynpcs.rig;

import dev.livelynpcs.anim.Animation;
import dev.livelynpcs.anim.Channel;
import dev.livelynpcs.anim.Easing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * How one NPC model (rig) moves for each of its viewers: the keyframes RigModel sends. Kept apart from
 * the entities and packets so it can be tested without a server.
 *
 * <p>Each viewer gets their own model, in packets only they receive: it looks at them (Gaze), plays
 * their own animations (a greeting when they walk up, talking when they click it, a beckon, one a
 * command played for them, an editor preview), and breathes, shifts its weight and now and then plays
 * an idle animation that everyone sees at the same moment. A viewer's own animation wins over the
 * shared one; a glance animation is skipped for viewers it is looking at (except when a command or a
 * preview plays it). Switching from one animation to another, or stopping one, blends over FADE ticks
 * instead of jumping. A viewer beyond animate-radius gets the rest pose once and then nothing until
 * they come back.
 *
 * <p>Each part moves by keyframes the client glides between: a keyframe gives a part's transformation
 * for a moment just ahead and glides there until a tick after the next keyframe is due, so the motion
 * never stops between packets. A part an animation moves, or one following a turn, gets a keyframe
 * every key ticks (npcs.key-ticks); a resting part only every quarter breath, on the NPC's own beat. A
 * keyframe that would not move a part is not sent, so legs that stand still cost nothing. Times are
 * the step clock's ticks, which go up by step ticks a step.
 */
public final class Motion {

    /** Where keyframes go: glide this part to transformation t (RigPose.display's layout) over glide ticks. */
    public interface Keyframes {
        void key(Part part, float[] t, int glide);
    }

    /**
     * Why an animation plays. Shared ones (IDLE, EVERYONE) everyone sees together; the rest are one
     * viewer's own and win over the shared one. Within a slot a higher priority is not interrupted by a
     * lower one.
     */
    public enum Kind {
        /** Now and then, from the NPC's idle list. */
        IDLE(0, true, false),
        /** /npc play ... all. */
        EVERYONE(1, false, true),
        /** They walked up. */
        GREET(1, true, false),
        /** From across the square: come here (it looks at them while it beckons). */
        BECKON(1, true, false),
        /** They clicked it. */
        CLICKED(2, true, false),
        /** /npc play ... for them. */
        COMMAND(3, false, true),
        /** The web editor's draft. */
        PREVIEW(4, false, true);

        public final int priority;
        /** A glance animation playing for this reason is skipped for viewers the NPC is looking at. */
        public final boolean glanceRule;
        /** A looping animation playing for this reason goes round until stopped; otherwise it plays once. */
        public final boolean loops;

        Kind(int priority, boolean glanceRule, boolean loops) {
            this.priority = priority;
            this.glanceRule = glanceRule;
            this.loops = loops;
        }

        public boolean shared() {
            return this == IDLE || this == EVERYONE;
        }
    }

    /** An animation playing since start (step clock ticks). */
    public record Playing(Animation animation, int start, Kind kind) {

        public boolean loops() {
            return kind.loops && animation.loop();
        }

        /** Whether it has finished (a looping one only ends when stopped). */
        public boolean over(int now) {
            return !loops() && now - start >= animation.length();
        }

        /** Its channel values at this tick of the step clock. */
        public void sample(int tick, float[] out) {
            int t = tick - start;
            if (!loops()) t = Math.min(t, animation.length());
            animation.sample(t, out);
        }
    }

    private record Pending(Animation animation, Kind kind) {
    }

    /** What one viewer has been sent of a model, where it looks for them, and their own animation. */
    public static final class Watch {
        private final Gaze gaze;
        private Pending pending;
        private Playing own, shown;
        /** Beyond animate-radius (or not stepped yet): at rest, and nothing more is sent until they come back. */
        private boolean parked = true;
        /** Per part: the transformation last sent, when its next keyframe is due, and the body yaw it was worked out with. */
        private final float[][] sent = new float[PARTS][];
        private final int[] due = new int[PARTS];
        private final float[] keyBody = new float[PARTS];
        private float keyHead, keyPitch;
        /** Where the last animation left off, fading out from residualStart (null: nothing to fade). */
        private float[] residual;
        private int residualStart;
        private final boolean[] residualMoves = new boolean[PARTS];

        private Watch(Gaze gaze) {
            this.gaze = gaze;
        }

        /**
         * Plays an animation of their own from the next step. False (nothing changes) when something more
         * important is playing for them: a click does not cut a preview short, a greeting not a command.
         */
        public boolean request(Animation animation, Kind kind, int now) {
            if (kind.shared()) throw new IllegalArgumentException(kind + " is shared: use Motion.share");
            if (pending != null && pending.kind.priority > kind.priority) return false;
            if (own != null && !own.over(now) && own.kind.priority > kind.priority) return false;
            pending = new Pending(animation, kind);
            return true;
        }

        /** Ends their own animation if it plays (or is about to) for this reason; it blends back to rest. */
        public boolean stop(Kind kind) {
            boolean stopped = false;
            if (pending != null && pending.kind == kind) {
                pending = null;
                stopped = true;
            }
            if (own != null && own.kind == kind) {
                own = null;
                stopped = true;
            }
            return stopped;
        }

        /** Why their own animation plays (or is about to), or null when none does. */
        public Kind playing(int now) {
            if (pending != null) return pending.kind;
            return own != null && !own.over(now) ? own.kind : null;
        }

        /** Whether the NPC is looking at them. */
        public boolean looking() {
            return gaze.looking();
        }
    }

    /** A turn of the head smaller than this (degrees) waits for its next keyframe; so does a body turn under BODY_TURN. */
    public static final float LOOK = 2f, BODY_TURN = 0.5f;
    /** Ticks a viewer walking away sees the model glide back to rest over. */
    public static final int REST_GLIDE = 10;
    /** Ticks over which one animation blends into the next (or back to rest when stopped). */
    public static final int FADE = 10;
    private static final int PARTS = Part.values().length;
    /** Changes smaller than these (blocks, the body's height scale) are not worth a packet; turns under 0.25 degrees neither. */
    private static final float MOVE = 0.001f, STRETCH = 0.0005f;
    private static final float TURN_DOT = (float) Math.cos(Math.toRadians(0.25) / 2);
    /** The weight shift: where the hips go (pixels), how long a shift takes, and the ticks between two. */
    private static final float[] LEANS = {-0.5f, 0f, 0.5f};
    private static final int LEAN_TICKS = 40, LEAN_MIN = 100, LEAN_MAX = 220;

    private final double x, z, eyeY;
    private final float yaw, scale;
    private final boolean slim, canLook;
    private final Part[] parts;
    private final int key, step, breathTicks, slow, beat;
    /** This NPC's place in its breath (0 to 1), so no two breathe together. */
    private final double breathPhase;
    private final Random random;
    /** Each part's rest transformation, as the model spawns. */
    private final float[][] rest = new float[PARTS][];
    private float leanFrom, leanTo;
    private int leanStart, nextLean;
    /** The shared animation (idle, or played for everyone) and one waiting for the next step. */
    private Playing shared;
    private Pending pendingShared;
    /** Scratch for one viewer's step (everything runs on the main thread). */
    private final RigPose pose = new RigPose();
    private final float[] channels = new float[Channel.COUNT], target = new float[8];
    private int posedAt;

    /**
     * The motion of a model standing at x/z facing yaw, its eyes at eyeY, at scale. slim: the skin's arms;
     * canLook: it turns to look at people (npcs.yml look); held: it has a held-item display. It breathes once
     * every breathTicks; moving parts get a keyframe every keyTicks; it is stepped every stepTicks (which
     * must divide keyTicks).
     */
    public Motion(double x, double eyeY, double z, float yaw, float scale, boolean slim, boolean canLook, boolean held,
                  int breathTicks, int keyTicks, int stepTicks, Random random) {
        if (stepTicks < 1 || keyTicks % stepTicks != 0) throw new IllegalArgumentException("step " + stepTicks + " must divide key " + keyTicks);
        this.x = x;
        this.eyeY = eyeY;
        this.z = z;
        this.yaw = yaw;
        this.scale = scale;
        this.slim = slim;
        this.canLook = canLook;
        this.key = keyTicks;
        this.step = stepTicks;
        this.breathTicks = Math.max(1, breathTicks);
        this.random = random;
        List<Part> list = new ArrayList<>(List.of(Part.BODY_PARTS));
        if (held) list.add(Part.HELD);
        parts = list.toArray(new Part[0]);
        breathPhase = random.nextDouble();
        slow = Math.max(key, Math.round(this.breathTicks / 4f / step) * step);
        beat = random.nextInt(slow / step) * step;
        nextLean = random.nextInt(LEAN_MIN, LEAN_MAX);
        pose.yaw = yaw;
        pose.slim = slim;
        pose.solve();
        for (Part p : parts) {
            rest[p.ordinal()] = new float[8];
            pose.display(p, scale, rest[p.ordinal()]);
        }
    }

    /** The parts this model has (the six body parts, and the held item if it has one). */
    public Part[] parts() {
        return parts.clone();
    }

    /** A part's rest transformation (RigPose.display's layout): how it spawns. */
    public float[] rest(Part part) {
        return rest[part.ordinal()].clone();
    }

    /** How tall the model stands, in blocks. */
    public double height() {
        return RigPose.HEIGHT * scale / 16;
    }

    /** A new viewer's state: the model as it spawns for them, at rest and not yet looking. */
    public Watch watch() {
        Watch w = new Watch(new Gaze(yaw, step));
        for (Part p : parts) {
            w.sent[p.ordinal()] = rest[p.ordinal()].clone();
            w.keyBody[p.ordinal()] = yaw;
        }
        w.keyHead = yaw;
        return w;
    }

    /**
     * The NPC's own life, once a step for everyone before its viewers' steps: a shared animation asked
     * for starts, one that finished ends, and now and then it shifts its weight to the other foot.
     */
    public void live(int now) {
        if (pendingShared != null) {
            shared = new Playing(pendingShared.animation, now, pendingShared.kind);
            pendingShared = null;
        }
        if (shared != null && shared.over(now)) shared = null;
        if (now < nextLean) return;
        leanFrom = lean(now);
        float next;
        do {
            next = LEANS[random.nextInt(LEANS.length)];
        } while (next == leanTo);
        leanTo = next;
        leanStart = now;
        nextLean = now + random.nextInt(LEAN_MIN, LEAN_MAX);
    }

    /**
     * Plays a shared animation (IDLE or EVERYONE) for everyone from the next step. False when something
     * more important is already shared (an idle does not cut short one played for everyone).
     */
    public boolean share(Animation animation, Kind kind, int now) {
        if (!kind.shared()) throw new IllegalArgumentException(kind + " is not shared: use Watch.request");
        if (pendingShared != null && pendingShared.kind.priority > kind.priority) return false;
        if (shared != null && !shared.over(now) && shared.kind.priority > kind.priority) return false;
        pendingShared = new Pending(animation, kind);
        return true;
    }

    /** Ends the shared animation if it plays for this reason. */
    public boolean stopShared(Kind kind) {
        boolean stopped = false;
        if (pendingShared != null && pendingShared.kind == kind) {
            pendingShared = null;
            stopped = true;
        }
        if (shared != null && shared.kind == kind) {
            shared = null;
            stopped = true;
        }
        return stopped;
    }

    /** Why the shared animation plays (or is about to), or null when none does. */
    public Kind sharing(int now) {
        if (pendingShared != null) return pendingShared.kind;
        return shared != null && !shared.over(now) ? shared.kind : null;
    }

    /**
     * One step for one viewer, their eyes at ex/ey/ez: beyond animateRadius the model goes to rest once;
     * otherwise it looks at them (within lookRadius, or while it beckons them), starts an animation they
     * asked for, and every part that is due gets its next keyframe. Returns whether any keyframe went out.
     */
    public boolean step(Watch w, int now, double ex, double ey, double ez, double lookRadius, double animateRadius, Keyframes out) {
        if (w.pending != null) {
            w.own = new Playing(w.pending.animation, now, w.pending.kind);
            w.pending = null;
        }
        if (w.own != null && w.own.over(now)) w.own = null;
        double dx = ex - x, dy = ey - eyeY, dz = ez - z, far = dx * dx + dy * dy + dz * dz;
        if (far > animateRadius * animateRadius) return !w.parked && park(w, out);
        boolean beckoning = w.own != null && w.own.kind == Kind.BECKON;
        boolean looking = canLook && ((lookRadius > 0 && far <= lookRadius * lookRadius) || beckoning);
        w.gaze.step(looking, Look.yaw(dx, dz), Look.pitch(dx, dy, dz));
        Playing shown = pick(w, looking, now);
        // Something starting or ending (or the viewer coming back) puts every part on its beat at once.
        boolean fresh = w.parked || shown != w.shown;
        if (shown != w.shown) blend(w, shown, now);
        w.parked = false;
        w.shown = shown;
        if (w.residual != null && now >= w.residualStart + FADE) w.residual = null;

        float body = w.gaze.body(), head = body + w.gaze.head(), pitch = w.gaze.pitch();
        boolean sent = false;
        posedAt = Integer.MIN_VALUE;
        for (Part p : parts) {
            int i = p.ordinal();
            boolean busy = (shown != null && shown.animation.moves(p)) || (w.residual != null && w.residualMoves[i]);
            boolean turned = Look.apart(body, w.keyBody[i]) > BODY_TURN;
            boolean looked = p == Part.HEAD && (Look.apart(head, w.keyHead) > LOOK || Math.abs(pitch - w.keyPitch) > LOOK);
            // A resting part follows a turn at once; one already on the quick beat picks it up at its next keyframe.
            boolean follow = !busy && (turned || looked) && w.due[i] - now > key;
            if (!fresh && !follow && now < w.due[i]) continue;
            int gap = busy || turned || looked ? key : untilBeat(now);
            pose(w, shown, now + gap);
            pose.display(p, scale, target);
            w.due[i] = now + gap;
            w.keyBody[i] = body;
            if (p == Part.HEAD) {
                w.keyHead = head;
                w.keyPitch = pitch;
            }
            if (!differs(target, w.sent[i])) continue;
            System.arraycopy(target, 0, w.sent[i], 0, target.length);
            out.key(p, w.sent[i], gap + 1);
            sent = true;
        }
        return sent;
    }

    /** What this viewer sees play now: their own animation, else the shared one (glance ones skipped while it looks at them). */
    private Playing pick(Watch w, boolean looking, int now) {
        if (w.own != null && !(w.own.kind.glanceRule && w.own.animation.glance() && looking)) return w.own;
        if (shared != null && !shared.over(now) && !(shared.kind.glanceRule && shared.animation.glance() && looking)) return shared;
        return null;
    }

    /** The animation shown changes at now: fade out from where the old one was to where the new one starts. */
    private void blend(Watch w, Playing next, int now) {
        float[] from = new float[Channel.COUNT];
        if (!w.parked) {
            if (w.shown != null) w.shown.sample(now, from);
            if (w.residual != null) {
                double f = fade(w, now);
                for (int c = 0; c < from.length; c++) from[c] += (float) (w.residual[c] * f);
            }
        }
        float[] to = new float[Channel.COUNT];
        if (next != null) next.sample(now, to);
        boolean any = false;
        Arrays.fill(w.residualMoves, false);
        for (Channel c : Channel.ALL) {
            from[c.ordinal()] -= to[c.ordinal()];
            if (Math.abs(from[c.ordinal()]) < 1e-3f) {
                from[c.ordinal()] = 0;
                continue;
            }
            any = true;
            for (Part p : parts) if (c.moves(p)) w.residualMoves[p.ordinal()] = true;
        }
        w.residual = any ? from : null;
        w.residualStart = now;
    }

    private static double fade(Watch w, int tick) {
        return 1 - Easing.EASE_IN_OUT.apply((tick - w.residualStart) / (double) FADE);
    }

    /** A viewer walked out of animate-radius: every part glides back to rest, and their gaze is dropped. */
    private boolean park(Watch w, Keyframes out) {
        boolean sent = false;
        for (Part p : parts) {
            int i = p.ordinal();
            w.keyBody[i] = yaw;
            w.due[i] = 0;
            if (!differs(rest[i], w.sent[i])) continue;
            System.arraycopy(rest[i], 0, w.sent[i], 0, rest[i].length);
            out.key(p, w.sent[i], REST_GLIDE);
            sent = true;
        }
        w.keyHead = yaw;
        w.keyPitch = 0;
        w.gaze.reset();
        w.shown = null;
        w.residual = null;
        w.parked = true;
        return sent;
    }

    /** Works out the pose this viewer should see at tick (once per tick per step). */
    private void pose(Watch w, Playing shown, int tick) {
        if (tick == posedAt) return;
        posedAt = tick;
        if (shown != null) shown.sample(tick, channels);
        else Arrays.fill(channels, 0f);
        if (w.residual != null) {
            double f = fade(w, tick);
            if (f > 0) for (int c = 0; c < channels.length; c++) channels[c] += (float) (w.residual[c] * f);
        }
        System.arraycopy(channels, 0, pose.channels, 0, channels.length);
        pose.yaw = yaw;
        pose.slim = slim;
        pose.gazeBody = w.gaze.bodyTurn();
        pose.gazeYaw = w.gaze.head();
        pose.gazePitch = w.gaze.pitch();
        pose.breath = breath(tick);
        pose.lean = lean(tick);
        pose.solve();
    }

    /** How far into a breath at tick, 0 to 1. */
    private float breath(int tick) {
        return (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * (tick / (double) breathTicks + breathPhase)));
    }

    /** The hips' shift at tick: easing from the last weight to the new one over LEAN_TICKS. */
    private float lean(int tick) {
        float t = Math.clamp((tick - leanStart) / (float) LEAN_TICKS, 0f, 1f);
        return leanFrom + (leanTo - leanFrom) * t * t * (3 - 2 * t);
    }

    /** Ticks to this NPC's next beat for resting parts, at least a keyframe gap away. */
    private int untilBeat(int now) {
        int gap = slow - Math.floorMod(now - beat, slow);
        return gap < key ? gap + slow : gap;
    }

    /** Whether a part's transformation moved enough since the last one sent to be worth a packet. */
    static boolean differs(float[] a, float[] b) {
        for (int i = 0; i < 3; i++) if (Math.abs(a[i] - b[i]) > MOVE) return true;
        float dot = Math.abs(a[3] * b[3] + a[4] * b[4] + a[5] * b[5] + a[6] * b[6]);
        return dot < TURN_DOT || Math.abs(a[7] - b[7]) > STRETCH;
    }
}
