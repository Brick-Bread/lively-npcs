package dev.livelynpcs.rig;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.livelynpcs.anim.Channel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the rig maths (RigPose) against src/test/resources/pose-fixtures.json. The web editor's 3D
 * view must draw the same picture from the same numbers, and tools/check-editor-pose.mjs checks its
 * JavaScript against the same file, so the file's shape is a contract:
 *
 * <pre>
 * {
 *   "about": "...",                         // what this is (text)
 *   "parts": ["head", "body", "right_arm", "left_arm", "right_leg", "left_leg", "held"],
 *   "poses": [ {
 *     "name": "rest facing south",          // for people
 *     "input": {
 *       "yaw": 0,                           // NPC yaw Y, degrees (Minecraft: 0 faces south +z, 90 west -x)
 *       "gazeBody": 0, "gazeYaw": 0, "gazePitch": 0,   // gb, gy, gp: where it looks, degrees
 *       "breath": 0,                        // b, 0..1
 *       "lean": 0,                          // weight shift, pixels (hips towards its left)
 *       "slim": false,                      // slim (3 pixel) arms
 *       "scale": 0.9375,                    // npcs.scale, for the display values
 *       "channels": { "right-arm-forward": 90, ... }   // Channel keys; a channel left out is 0
 *     },
 *     "expect": {
 *       "bodyTurn": [x, y, z, w],           // Rb, quaternion
 *       "root": [x, y, z],                  // o, pixels from the feet, already turned with the body
 *       "stretch": 1.0,                     // k, the body's height stretch
 *       "parts": { "head": {
 *         "origin": [x, y, z],              // body frame, pixels (DESIGN.md step 3)
 *         "rotation": [x, y, z, w],         // body frame, quaternion
 *         "translation": [x, y, z],         // the display's, blocks: (o + Rb·origin)·scale/16
 *         "leftRotation": [x, y, z, w],     // the display's: Rb·rotation
 *         "scale": [x, y, z],               // the display's: (scale, scale·k for the body else scale, scale)
 *         "rightRotation": [x, y, z, w]     // the display's: [0,1,0,0] for body parts, [0,0,0,1] for held
 *       }, ... }
 *     }
 *   }, ... ]
 * }
 * </pre>
 *
 * Quaternions are (x, y, z, w) as JOML, written with w &gt;= 0; q and -q are the same turn, so compare
 * them with |q1·q2| close to 1 (or after making w &gt;= 0). Numbers have 9 decimals; agreeing to 1e-6
 * is agreeing. Run the tests with the environment variable LIVELYNPCS_WRITE_FIXTURES=1 to rewrite the
 * file from the poses below after a deliberate change to the maths (then tell the editor builder).
 */
class PoseFixturesTest {

    private static final String FILE = "pose-fixtures.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** One fixture's inputs. */
    private record In(String name, float yaw, float gazeBody, float gazeYaw, float gazePitch, float breath, float lean,
                      boolean slim, float scale, Map<String, Float> channels) {

        RigPose pose() {
            RigPose p = new RigPose();
            p.yaw = yaw;
            p.gazeBody = gazeBody;
            p.gazeYaw = gazeYaw;
            p.gazePitch = gazePitch;
            p.breath = breath;
            p.lean = lean;
            p.slim = slim;
            for (Map.Entry<String, Float> e : channels.entrySet()) {
                Channel c = Channel.byKey(e.getKey());
                assertNotNull(c, "no channel " + e.getKey());
                p.channels[c.ordinal()] = e.getValue();
            }
            return p.solve();
        }
    }

    private static final class Builder {
        private final String name;
        private float yaw, gazeBody, gazeYaw, gazePitch, breath, lean, scale = 0.9375f;
        private boolean slim;
        private final Map<String, Float> channels = new LinkedHashMap<>();

        Builder(String name) {
            this.name = name;
        }

        Builder yaw(float v) { yaw = v; return this; }
        Builder gaze(float body, float headYaw, float headPitch) { gazeBody = body; gazeYaw = headYaw; gazePitch = headPitch; return this; }
        Builder breath(float v) { breath = v; return this; }
        Builder lean(float v) { lean = v; return this; }
        Builder slim() { slim = true; return this; }
        Builder scale(float v) { scale = v; return this; }
        Builder ch(String key, float v) { channels.put(key, v); return this; }

        In done() {
            return new In(name, yaw, gazeBody, gazeYaw, gazePitch, breath, lean, slim, scale, channels);
        }
    }

    private static Builder pose(String name) {
        return new Builder(name);
    }

    /** The poses in the file: rest at several yaws, each kind of move alone, and mixtures. */
    static List<In> poses() {
        List<In> out = new ArrayList<>();
        out.add(pose("rest facing south").done());
        out.add(pose("rest facing west").yaw(90).done());
        out.add(pose("rest facing north-east").yaw(-135).done());
        out.add(pose("rest facing north, slim").yaw(180).slim().done());
        out.add(pose("head yaw").ch("head-yaw", 30).done());
        out.add(pose("head pitch and roll").ch("head-pitch", 20).ch("head-roll", 10).done());
        out.add(pose("gaze: body, head yaw and pitch").yaw(40).gaze(20, 40, -15).done());
        out.add(pose("gaze plus head yaw past the limit").gaze(0, 70, 50).ch("head-yaw", 30).ch("head-pitch", 30).done());
        out.add(pose("deep breath").breath(1).done());
        out.add(pose("half breath, slim").breath(0.5f).slim().yaw(-60).done());
        out.add(pose("weight on the left foot").lean(0.5f).done());
        out.add(pose("weight on the right foot, facing south-west").lean(-0.5f).yaw(45).done());
        out.add(pose("right arm pointing ahead").ch("right-arm-forward", 90).done());
        out.add(pose("right arm out to its side").ch("right-arm-out", 90).done());
        out.add(pose("right arm ahead and across").ch("right-arm-forward", 90).ch("right-arm-across", 30).done());
        out.add(pose("left arm waving, slim").slim().ch("left-arm-forward", 160).ch("left-arm-out", -8).done());
        out.add(pose("stretch top").ch("right-arm-forward", 176).ch("left-arm-forward", 176).ch("right-arm-out", -12)
                .ch("left-arm-out", -12).ch("shoulders", 1).ch("head-pitch", -16).done());
        out.add(pose("bow").ch("torso-pitch", 30).done());
        out.add(pose("torso roll and twist").ch("torso-roll", -15).ch("torso-yaw", 20).done());
        out.add(pose("bent with arms forward").ch("torso-pitch", 20).ch("right-arm-forward", 45).ch("left-arm-forward", 45)
                .ch("head-pitch", 10).done());
        out.add(pose("root step").ch("root-x", 3).ch("root-y", 2).ch("root-z", -4).done());
        out.add(pose("root spin").yaw(30).ch("root-yaw", 90).done());
        out.add(pose("greet frame: hop and arm up").ch("root-y", 3.5f).ch("right-arm-forward", 90).done());
        out.add(pose("legs forward and out").ch("right-leg-forward", 30).ch("left-leg-out", 15).done());
        out.add(pose("legs with a lean").lean(0.5f).ch("left-leg-forward", -20).ch("right-leg-out", 10).done());
        out.add(pose("held item, arm reaching").ch("left-arm-forward", 70).ch("left-arm-across", 20).done());
        out.add(pose("held item, slim, arm out, breathing").slim().breath(1).ch("left-arm-out", 40).ch("left-arm-forward", 10).done());
        out.add(pose("scratch head frame").ch("right-arm-forward", 204).ch("right-arm-out", 10).ch("right-arm-across", 10)
                .ch("head-pitch", 8).ch("head-roll", 5).done());
        out.add(pose("clap frame").ch("right-arm-forward", 65).ch("left-arm-forward", 65).ch("right-arm-across", 25)
                .ch("left-arm-across", 25).ch("head-pitch", 6).done());
        out.add(pose("everything at once").yaw(200).gaze(-15, -30, 25).breath(0.7f).lean(-0.3f).slim().scale(1.5f)
                .ch("torso-pitch", 10).ch("torso-roll", 5).ch("torso-yaw", -10).ch("root-x", 1).ch("root-y", 1).ch("root-z", 1)
                .ch("root-yaw", -20).ch("head-yaw", 10).ch("head-pitch", 5).ch("head-roll", -5).ch("shoulders", 0.5f)
                .ch("right-arm-forward", 45).ch("right-arm-out", 20).ch("right-arm-across", 10).ch("left-arm-forward", 120)
                .ch("left-arm-out", 5).ch("left-arm-across", -10).ch("right-leg-forward", 15).ch("right-leg-out", 5)
                .ch("left-leg-forward", -10).ch("left-leg-out", 3).done());
        out.add(pose("arms at the ends of their range").ch("right-arm-forward", 220).ch("right-arm-out", 180)
                .ch("left-arm-forward", -90).ch("left-arm-out", -60).ch("left-arm-across", 90).done());
        return out;
    }

    @Test
    void javaMathsStillGiveTheFixtures() throws IOException {
        JsonObject file;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FILE)) {
            assertNotNull(in, FILE + " is missing");
            file = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonArray poses = file.getAsJsonArray("poses");
        assertTrue(poses.size() >= 30, "about 30 poses");
        float[] t = new float[8];
        for (JsonElement element : poses) {
            JsonObject fixture = element.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            JsonObject in = fixture.getAsJsonObject("input");
            Map<String, Float> channels = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("channels").entrySet()) channels.put(e.getKey(), e.getValue().getAsFloat());
            In input = new In(name, in.get("yaw").getAsFloat(), in.get("gazeBody").getAsFloat(), in.get("gazeYaw").getAsFloat(),
                    in.get("gazePitch").getAsFloat(), in.get("breath").getAsFloat(), in.get("lean").getAsFloat(),
                    in.get("slim").getAsBoolean(), in.get("scale").getAsFloat(), channels);
            RigPose p = input.pose();
            JsonObject expect = fixture.getAsJsonObject("expect");
            sameTurn(numbers(expect.getAsJsonArray("bodyTurn")), p.bodyTurn(), name + " bodyTurn");
            same(numbers(expect.getAsJsonArray("root")), p.root(), 1e-6, name + " root");
            assertEquals(expect.get("stretch").getAsDouble(), p.stretch(), 1e-6, name + " stretch");
            JsonObject parts = expect.getAsJsonObject("parts");
            for (Part part : Part.values()) {
                JsonObject e = parts.getAsJsonObject(name(part));
                String where = name + " " + name(part);
                same(numbers(e.getAsJsonArray("origin")), p.origin(part), 1e-6, where + " origin");
                sameTurn(numbers(e.getAsJsonArray("rotation")), p.rotation(part), where + " rotation");
                p.display(part, input.scale(), t);
                same(numbers(e.getAsJsonArray("translation")), new double[] {t[0], t[1], t[2]}, 1e-5, where + " translation");
                sameTurn(numbers(e.getAsJsonArray("leftRotation")), new double[] {t[3], t[4], t[5], t[6]}, where + " leftRotation");
                double[] scale = numbers(e.getAsJsonArray("scale"));
                assertEquals(scale[1], t[7], 1e-5, where + " scale");
                assertEquals(input.scale(), scale[0], 1e-6, where + " scale x");
                float[] right = RigPose.rightRotation(part);
                sameTurn(numbers(e.getAsJsonArray("rightRotation")), new double[] {right[0], right[1], right[2], right[3]}, where + " right");
            }
        }
    }

    /** Rewrites the file from poses() when LIVELYNPCS_WRITE_FIXTURES=1 (a deliberate change to the maths). */
    @Test
    void writeFixturesWhenAsked() throws IOException {
        if (!"1".equals(System.getenv("LIVELYNPCS_WRITE_FIXTURES"))) return;
        JsonObject root = new JsonObject();
        root.addProperty("about", "Lively NPCs rig poses (DESIGN.md 'Coordinates and the rig maths'): inputs and the parts' "
                + "origins and rotations in the body frame (rig pixels, +y up, facing +z, its right -x), and the item display "
                + "transformations made from them. Written by PoseFixturesTest (see its javadoc for the shape); checked by it "
                + "and by tools/check-editor-pose.mjs.");
        JsonArray parts = new JsonArray();
        for (Part part : Part.values()) parts.add(name(part));
        root.add("parts", parts);
        JsonArray list = new JsonArray();
        for (In in : poses()) list.add(fixture(in));
        root.add("poses", list);
        Path file = Path.of("src", "test", "resources", FILE);
        Files.createDirectories(file.getParent());
        // Lists of numbers on one line each, so the file reads like a table.
        String json = java.util.regex.Pattern.compile("\\[\\s*(-?[0-9.Ee+-]+(?:,\\s*-?[0-9.Ee+-]+)*)\\s*]")
                .matcher(GSON.toJson(root)).replaceAll(m -> "[" + m.group(1).replaceAll(",\\s+", ", ") + "]");
        Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
    }

    private static JsonObject fixture(In in) {
        RigPose p = in.pose();
        JsonObject o = new JsonObject();
        o.addProperty("name", in.name());
        JsonObject input = new JsonObject();
        input.add("yaw", given(in.yaw()));
        input.add("gazeBody", given(in.gazeBody()));
        input.add("gazeYaw", given(in.gazeYaw()));
        input.add("gazePitch", given(in.gazePitch()));
        input.add("breath", given(in.breath()));
        input.add("lean", given(in.lean()));
        input.addProperty("slim", in.slim());
        input.add("scale", given(in.scale()));
        JsonObject channels = new JsonObject();
        for (Map.Entry<String, Float> e : in.channels().entrySet()) channels.add(e.getKey(), given(e.getValue()));
        input.add("channels", channels);
        o.add("input", input);

        JsonObject expect = new JsonObject();
        double[] rb = p.bodyTurn();
        expect.add("bodyTurn", array(Turns.canonical(rb)));
        expect.add("root", array(p.root()));
        expect.add("stretch", num(p.stretch()));
        JsonObject parts = new JsonObject();
        double k = in.scale() / 16.0;
        for (Part part : Part.values()) {
            JsonObject e = new JsonObject();
            e.add("origin", array(p.origin(part)));
            e.add("rotation", array(Turns.canonical(p.rotation(part))));
            double[] at = Turns.add(p.root(), Turns.rotate(rb, p.origin(part)));
            e.add("translation", array(new double[] {at[0] * k, at[1] * k, at[2] * k}));
            e.add("leftRotation", array(Turns.canonical(Turns.mul(rb, p.rotation(part)))));
            double height = part == Part.BODY ? in.scale() * p.stretch() : in.scale();
            e.add("scale", array(new double[] {in.scale(), height, in.scale()}));
            float[] right = RigPose.rightRotation(part);
            e.add("rightRotation", array(new double[] {right[0], right[1], right[2], right[3]}));
            parts.add(name(part), e);
        }
        expect.add("parts", parts);
        o.add("expect", expect);
        return o;
    }

    static String name(Part part) {
        return part == Part.HELD ? "held" : part.model;
    }

    /** An input as written in the pose list (0.7, not 0.699999988). */
    private static JsonElement given(float v) {
        BigDecimal d = new BigDecimal(Float.toString(v)).stripTrailingZeros();
        if (d.signum() == 0) d = BigDecimal.ZERO;
        return new com.google.gson.JsonPrimitive(d.scale() <= 0 ? (Number) d.toBigInteger() : d);
    }

    private static JsonElement num(double v) {
        BigDecimal d = BigDecimal.valueOf(v).setScale(9, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros();
        if (d.signum() == 0) d = BigDecimal.ZERO;
        return new com.google.gson.JsonPrimitive(d.scale() <= 0 ? (Number) d.toBigInteger() : d);
    }

    private static JsonArray array(double[] values) {
        JsonArray a = new JsonArray();
        for (double v : values) a.add(num(v));
        return a;
    }

    private static double[] numbers(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static void same(double[] expected, double[] actual, double eps, String where) {
        assertEquals(expected.length, actual.length, where);
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual[i], eps, where + "[" + i + "]");
    }

    /** The same turn: q or -q, whichever is written closer, agrees to 1e-5 in every component. */
    private static void sameTurn(double[] expected, double[] actual, String where) {
        double dot = 0;
        for (int i = 0; i < 4; i++) dot += expected[i] * actual[i];
        double sign = dot < 0 ? -1 : 1;
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], sign * actual[i], 1e-5, where + ": expected " + java.util.Arrays.toString(expected)
                    + " got " + java.util.Arrays.toString(actual));
        }
    }
}
