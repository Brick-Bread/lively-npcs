package dev.livelynpcs.npc;

import dev.livelynpcs.rig.Look;

import java.util.List;

/**
 * Which NPC beckons a player over (DESIGN.md: players between beckon-from and beckon-to blocks away,
 * facing an NPC within FACING degrees). Of the NPCs that may beckon them now (the caller has checked
 * the timers and that the NPC can), it picks the one they face most; on a tie, the nearer. Kept free of
 * server classes so it can be tested.
 */
public final class Beckon {

    /** A player counts as facing an NPC within this many degrees either side (left-right only). */
    public static final double FACING = 60;
    /** The same NPC beckons the same player at most this often. */
    public static final int SAME_NPC_TICKS = 30 * 20;

    /** Where an NPC's eyes are. */
    public record Spot(double x, double y, double z) {
    }

    private Beckon() {
    }

    /**
     * The index in spots of the NPC that beckons a player whose eyes are at ex/ey/ez and who faces yaw,
     * or -1 when none is between from and to blocks away (eyes to eyes) and within FACING of where they face.
     */
    public static int pick(double ex, double ey, double ez, float yaw, List<Spot> spots, double from, double to) {
        if (from <= 0 || to <= from) return -1;
        int best = -1;
        double bestAngle = Double.MAX_VALUE, bestFar = Double.MAX_VALUE;
        for (int i = 0; i < spots.size(); i++) {
            Spot s = spots.get(i);
            double dx = s.x - ex, dy = s.y - ey, dz = s.z - ez;
            double far = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (far < from || far > to) continue;
            double angle = Look.apart(yaw, Look.yaw(dx, dz));
            if (angle > FACING) continue;
            if (angle < bestAngle - 1e-6 || (Math.abs(angle - bestAngle) <= 1e-6 && far < bestFar)) {
                best = i;
                bestAngle = angle;
                bestFar = far;
            }
        }
        return best;
    }
}
