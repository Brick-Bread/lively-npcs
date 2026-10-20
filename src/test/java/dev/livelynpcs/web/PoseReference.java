package dev.livelynpcs.web;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DESIGN.md's rig maths ("Coordinates and the rig maths") written with JOML, the library the runtime
 * uses, as the reference the editor's rig.js is checked against: EditorPoseFixtureTest keeps
 * src/test/resources/editor-pose-fixtures.json equal to this, and tools/check-editor-pose.mjs checks
 * rig.js against that file (until the runtime's pose-fixtures.json exists, then against both).
 *
 * <p>Rewrite the fixture after changing the poses or the maths (needs only JOML):
 * {@code java -cp <joml.jar> src/test/java/dev/livelynpcs/web/PoseReference.java src/test/resources/editor-pose-fixtures.json}
 */
final class PoseReference {

    static final Vector3d UP = new Vector3d(0, 1, 0), ACROSS = new Vector3d(1, 0, 0), FRONT = new Vector3d(0, 0, 1);
    static final List<String> PARTS = List.of("head", "body", "right_arm", "left_arm", "right_leg", "left_leg", "held");
    static final double SCALE = 0.9375;

    /** One moment's inputs: channel values by key, and the live layers. */
    record Input(String name, Map<String, Double> channels, double yaw, double gazeBody, double gazeYaw, double gazePitch,
                 double breath, double shift, boolean slim) {
    }

    /** A part in the body frame (step 3) and as its display (step 4). */
    record Part(Vector3d origin, Quaterniond rotation, Vector3d translation, Quaterniond leftRotation, Vector3d scale,
                Quaterniond rightRotation) {
    }

    static Quaterniond about(Vector3d axis, double deg) {
        return new Quaterniond().rotationAxis(Math.toRadians(deg), axis.x, axis.y, axis.z);
    }

    /** a·b·c...: turns by the last first. */
    static Quaterniond mul(Quaterniond... qs) {
        Quaterniond r = new Quaterniond(qs[0]);
        for (int i = 1; i < qs.length; i++) r.mul(qs[i]);
        return r;
    }

    static Vector3d turn(Quaterniond q, Vector3d v) {
        return q.transform(new Vector3d(v));
    }

    static Vector3d plus(Vector3d a, Vector3d b) {
        return new Vector3d(a).add(b);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static Map<String, Part> pose(Input in, double scale) {
        Map<String, Double> c = in.channels();
        java.util.function.ToDoubleFunction<String> v = key -> c.getOrDefault(key, 0.0);
        double b = in.breath(), lam = in.shift(), e = in.slim() ? 0.5 : 0;

        double psi = in.yaw() + in.gazeBody() + v.applyAsDouble("root-yaw");
        Quaterniond rb = about(UP, -psi);
        Vector3d o = turn(rb, new Vector3d(-v.applyAsDouble("root-x"), v.applyAsDouble("root-y"), v.applyAsDouble("root-z")));

        double tau = Math.toDegrees(Math.asin(clamp(-lam / 12, -1, 1)));
        double d = -12 * (1 - Math.cos(Math.toRadians(tau)));
        Vector3d hips = new Vector3d(lam, 12 + d, 0);
        double rho = 0.3 * b;
        Quaterniond rt = mul(about(UP, -v.applyAsDouble("torso-yaw")), about(ACROSS, v.applyAsDouble("torso-pitch")),
                about(FRONT, v.applyAsDouble("torso-roll")));
        Vector3d neck = plus(hips, turn(rt, new Vector3d(0, 12 + rho, 0)));
        double k = 1 + rho / 12;
        double shoulders = v.applyAsDouble("shoulders");

        Map<String, Vector3d> origins = new LinkedHashMap<>();
        Map<String, Quaterniond> rotations = new LinkedHashMap<>();
        double hy = clamp(in.gazeYaw() + v.applyAsDouble("head-yaw"), -80, 80);
        double hp = clamp(in.gazePitch() + v.applyAsDouble("head-pitch") - 1.5 * b, -60, 60);
        origins.put("head", neck);
        rotations.put("head", mul(rt, about(UP, -hy), about(ACROSS, hp), about(FRONT, v.applyAsDouble("head-roll"))));
        origins.put("body", neck);
        rotations.put("body", rt);

        Quaterniond leftRa = null;
        for (String side : List.of("right", "left")) {
            int s = side.equals("right") ? -1 : 1;
            Quaterniond ra = mul(about(FRONT, s * (v.applyAsDouble(side + "-arm-out") + 2.5 * b)),
                    about(UP, -s * v.applyAsDouble(side + "-arm-across")), about(ACROSS, -v.applyAsDouble(side + "-arm-forward")));
            if (s == 1) leftRa = ra;
            Vector3d pivot = plus(hips, turn(rt, new Vector3d(5 * s, 10 + rho + shoulders - e, 0)));
            Quaterniond r = mul(rt, ra);
            origins.put(side + "_arm", plus(pivot, turn(r, new Vector3d(s * (1 - e), 2, 0))));
            rotations.put(side + "_arm", r);
        }
        for (String side : List.of("right", "left")) {
            int s = side.equals("right") ? -1 : 1;
            origins.put(side + "_leg", new Vector3d(2 * s + lam, 12 + d, 0));
            rotations.put(side + "_leg", mul(about(FRONT, tau + s * v.applyAsDouble(side + "-leg-out")),
                    about(ACROSS, -v.applyAsDouble(side + "-leg-forward"))));
        }
        Vector3d grip = plus(hips, turn(rt, new Vector3d(5 - e, 10 + rho + shoulders - e, 0)));
        origins.put("held", plus(grip, turn(mul(rt, leftRa), new Vector3d(1, -10, 2))));
        rotations.put("held", mul(rt, leftRa, about(ACROSS, 90)));

        Map<String, Part> out = new LinkedHashMap<>();
        for (String name : PARTS) {
            Vector3d p = origins.get(name);
            Quaterniond r = rotations.get(name);
            Vector3d translation = plus(o, turn(rb, p)).mul(scale / 16);
            Quaterniond left = mul(rb, r);
            Vector3d size = new Vector3d(scale, name.equals("body") ? scale * k : scale, scale);
            Quaterniond right = name.equals("held") ? new Quaterniond() : new Quaterniond(0, 1, 0, 0);
            out.put(name, new Part(p, r, translation, left, size, right));
        }
        return out;
    }

    private static Map<String, Double> values(Object... pairs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        return m;
    }

    /** The poses in the fixture: each channel group, the root with an NPC yaw, every live layer, slim arms, the clamps. */
    static final List<Input> INPUTS = List.of(
            new Input("rest", values(), 0, 0, 0, 0, 0, 0, false),
            new Input("greet: hand up with a hop", values("root-y", 3.5, "right-arm-forward", 160, "right-arm-out", -8), 0, 0, 0, 0, 0, 0, false),
            new Input("arms: forward, out, across, shrug", values("left-arm-forward", 60, "left-arm-out", 11, "left-arm-across", 25,
                    "right-arm-forward", -30, "right-arm-across", 10, "right-arm-out", 35, "shoulders", 1), 0, 0, 0, 0, 0, 0, false),
            new Input("torso and head", values("torso-pitch", 20, "torso-roll", -8, "torso-yaw", 15, "head-yaw", 30, "head-pitch", -12,
                    "head-roll", 5), 0, 0, 0, 0, 0, 0, false),
            new Input("legs", values("right-leg-forward", 14, "right-leg-out", 5, "left-leg-forward", -20, "left-leg-out", 10), 0, 0, 0, 0, 0, 0, false),
            new Input("root steps and spins, NPC facing west", values("root-x", 3, "root-z", -2, "root-yaw", 45, "root-y", 1.25), 90, 0, 0, 0, 0, 0, false),
            new Input("live layers: gaze, breath, weight shift", values("head-yaw", 40, "right-arm-forward", 20), -135, 10, 50, 10, 0.6, 1.5, false),
            new Input("slim arms holding an item up", values("left-arm-forward", 90, "left-arm-out", -5, "shoulders", 0.8, "torso-yaw", -10), 30, 0, 0, 0,
                    0.25, -0.75, true),
            new Input("head clamps", values("head-pitch", 50, "head-yaw", -70), 0, 0, -30, 30, 1, 0, false),
            new Input("scratch head", values("right-arm-forward", 204, "right-arm-out", 10, "right-arm-across", 10, "head-pitch", 8, "head-roll", 5,
                    "torso-pitch", 5), 180, -20, 0, 0, 0.5, 2.5, true));

    // ---- writing the fixture ----

    private static String num(double v) {
        if (Math.abs(v) < 1e-12) v = 0;
        String s = String.format(Locale.ROOT, "%.10f", v);
        s = s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        return s.equals("-0") ? "0" : s;
    }

    private static String vec(Vector3d v) {
        return "[" + num(v.x) + ", " + num(v.y) + ", " + num(v.z) + "]";
    }

    private static String quat(Quaterniond q) {
        // One sign for each rotation (w >= 0), so the file reads the same every time.
        double s = q.w < 0 || (q.w == 0 && (q.x < 0 || (q.x == 0 && (q.y < 0 || (q.y == 0 && q.z < 0))))) ? -1 : 1;
        return "[" + num(s * q.x) + ", " + num(s * q.y) + ", " + num(s * q.z) + ", " + num(s * q.w) + "]";
    }

    static String fixture() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"about\": \"Poses and where DESIGN.md's rig maths puts each part, made with JOML by PoseReference.java (the editor's tests).")
                .append(" Per part: origin and rotation in the body frame (step 3, pixels and [x, y, z, w]); translation (blocks), leftRotation, scale")
                .append(" and rightRotation of its item display (step 4). tools/check-editor-pose.mjs checks the editor's rig.js against it.\",\n");
        out.append("  \"scale\": ").append(num(SCALE)).append(",\n  \"poses\": [\n");
        for (int i = 0; i < INPUTS.size(); i++) {
            Input in = INPUTS.get(i);
            out.append("    {\n      \"name\": \"").append(in.name()).append("\",\n      \"channels\": {");
            int j = 0;
            for (Map.Entry<String, Double> e : in.channels().entrySet()) {
                out.append(j++ == 0 ? "" : ", ").append('"').append(e.getKey()).append("\": ").append(num(e.getValue()));
            }
            out.append("},\n      \"live\": {\"yaw\": ").append(num(in.yaw())).append(", \"gazeBody\": ").append(num(in.gazeBody()))
                    .append(", \"gazeYaw\": ").append(num(in.gazeYaw())).append(", \"gazePitch\": ").append(num(in.gazePitch()))
                    .append(", \"breath\": ").append(num(in.breath())).append(", \"shift\": ").append(num(in.shift()))
                    .append(", \"slim\": ").append(in.slim()).append("},\n      \"parts\": {\n");
            Map<String, Part> parts = pose(in, SCALE);
            int n = 0;
            for (Map.Entry<String, Part> e : parts.entrySet()) {
                Part p = e.getValue();
                out.append("        \"").append(e.getKey()).append("\": {\"origin\": ").append(vec(p.origin())).append(", \"rotation\": ").append(quat(p.rotation()))
                        .append(",\n          \"translation\": ").append(vec(p.translation())).append(", \"leftRotation\": ").append(quat(p.leftRotation()))
                        .append(", \"scale\": ").append(vec(p.scale())).append(", \"rightRotation\": ").append(quat(p.rightRotation())).append('}')
                        .append(++n < parts.size() ? ",\n" : "\n");
            }
            out.append("      }\n    }").append(i + 1 < INPUTS.size() ? ",\n" : "\n");
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    public static void main(String[] args) throws IOException {
        Path file = Path.of(args.length > 0 ? args[0] : "src/test/resources/editor-pose-fixtures.json");
        Files.writeString(file, fixture(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + INPUTS.size() + " poses to " + file);
    }
}
