package dev.livelynpcs.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.livelynpcs.anim.Channel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's pose fixture (what tools/check-editor-pose.mjs checks rig.js against) is what JOML
 * gives for DESIGN.md's maths, and a few of its numbers are what the design says they are.
 */
class EditorPoseFixtureTest {

    private static JsonObject fixture() throws IOException {
        try (InputStream in = EditorPoseFixtureTest.class.getResourceAsStream("/editor-pose-fixtures.json")) {
            assertNotNull(in, "src/test/resources/editor-pose-fixtures.json is missing");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void near(double[] want, JsonArray got, String what) {
        assertEquals(want.length, got.size(), what);
        for (int i = 0; i < want.length; i++) assertEquals(want[i], got.get(i).getAsDouble(), 1e-6, what + "[" + i + "]");
    }

    private static void sameRotation(Quaterniond q, JsonArray got, String what) {
        double dot = q.x * got.get(0).getAsDouble() + q.y * got.get(1).getAsDouble() + q.z * got.get(2).getAsDouble() + q.w * got.get(3).getAsDouble();
        assertEquals(1, Math.abs(dot), 1e-6, what + " (q and -q are the same turn)");
    }

    @Test
    void fixtureIsWhatJomlGives() throws IOException {
        JsonObject f = fixture();
        assertEquals(PoseReference.SCALE, f.get("scale").getAsDouble());
        JsonArray poses = f.getAsJsonArray("poses");
        assertEquals(PoseReference.INPUTS.size(), poses.size(), "rewrite the fixture: see PoseReference");
        for (int i = 0; i < poses.size(); i++) {
            JsonObject pose = poses.get(i).getAsJsonObject();
            PoseReference.Input in = PoseReference.INPUTS.get(i);
            assertEquals(in.name(), pose.get("name").getAsString());
            JsonObject channels = pose.getAsJsonObject("channels");
            assertEquals(in.channels().size(), channels.size(), in.name());
            for (Map.Entry<String, JsonElement> e : channels.entrySet()) {
                assertNotNull(Channel.byKey(e.getKey()), "a real channel: " + e.getKey());
                assertEquals(in.channels().get(e.getKey()), e.getValue().getAsDouble(), 1e-9);
            }
            JsonObject live = pose.getAsJsonObject("live");
            assertEquals(in.yaw(), live.get("yaw").getAsDouble(), 1e-9);
            assertEquals(in.breath(), live.get("breath").getAsDouble(), 1e-9);
            assertEquals(in.shift(), live.get("shift").getAsDouble(), 1e-9);
            assertEquals(in.slim(), live.get("slim").getAsBoolean());

            Map<String, PoseReference.Part> want = PoseReference.pose(in, PoseReference.SCALE);
            JsonObject parts = pose.getAsJsonObject("parts");
            assertEquals(PoseReference.PARTS, parts.keySet().stream().toList());
            for (String name : PoseReference.PARTS) {
                PoseReference.Part p = want.get(name);
                JsonObject got = parts.getAsJsonObject(name);
                String where = in.name() + " / " + name;
                near(new double[]{p.origin().x, p.origin().y, p.origin().z}, got.getAsJsonArray("origin"), where + " origin");
                sameRotation(p.rotation(), got.getAsJsonArray("rotation"), where + " rotation");
                near(new double[]{p.translation().x, p.translation().y, p.translation().z}, got.getAsJsonArray("translation"), where + " translation");
                sameRotation(p.leftRotation(), got.getAsJsonArray("leftRotation"), where + " left rotation");
                near(new double[]{p.scale().x, p.scale().y, p.scale().z}, got.getAsJsonArray("scale"), where + " scale");
                sameRotation(p.rightRotation(), got.getAsJsonArray("rightRotation"), where + " right rotation");
            }
        }
    }

    @Test
    void restPoseIsAPlayersShape() {
        Map<String, PoseReference.Part> rest = PoseReference.pose(PoseReference.INPUTS.getFirst(), 1);
        assertEquals(new Vector3d(0, 24, 0), rest.get("head").origin(), "the neck is 24 px up");
        assertEquals(new Vector3d(-6, 24, 0), rest.get("right_arm").origin(), "the right arm's top centre, to its right (-x)");
        assertEquals(new Vector3d(6, 24, 0), rest.get("left_arm").origin());
        assertEquals(new Vector3d(-2, 12, 0), rest.get("right_leg").origin());
        assertEquals(new Vector3d(6, 12, 2), rest.get("held").origin(), "the item in the left hand, as the game draws an off-hand item");
        Map<String, PoseReference.Part> slim = PoseReference.pose(new PoseReference.Input("slim", Map.of(), 0, 0, 0, 0, 0, 0, true), 1);
        assertEquals(new Vector3d(5.5, 23.5, 0), slim.get("left_arm").origin(), "slim arms are half a pixel in and down");
    }

    @Test
    void signsAreAsPeopleSeeThem() {
        // Positive head yaw turns to its right (-x); positive pitch looks down; arm forward 90 points ahead (+z).
        Map<String, PoseReference.Part> p = PoseReference.pose(new PoseReference.Input("signs",
                Map.of("head-yaw", 90.0, "right-arm-forward", 90.0, "left-arm-out", 90.0), 0, 0, 0, 0, 0, 0, false), 1);
        Vector3d face = p.get("head").rotation().transform(new Vector3d(0, 0, 1));
        assertEquals(-Math.sin(Math.toRadians(80)), face.x, 1e-9, "head turned to its right, as far as 80 degrees: " + face);
        assertEquals(Math.cos(Math.toRadians(80)), face.z, 1e-9);
        Vector3d hand = p.get("right_arm").rotation().transform(new Vector3d(0, -12, 0));
        assertTrue(hand.z > 11.99, "right arm points ahead: " + hand);
        Vector3d out = p.get("left_arm").rotation().transform(new Vector3d(0, -12, 0));
        assertTrue(out.x > 11.99, "left arm out to its left (+x): " + out);
        // Yaw 90 faces west (-x).
        Map<String, PoseReference.Part> west = PoseReference.pose(new PoseReference.Input("west", Map.of(), 90, 0, 0, 0, 0, 0, false), 1);
        Vector3d facing = west.get("body").leftRotation().transform(new Vector3d(0, 0, 1));
        assertTrue(facing.x < -0.99, "yaw 90 faces west: " + facing);
    }
}
