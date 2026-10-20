package dev.livelynpcs.anim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The presets as they ship, and the server's own animations: save, delete, reload. */
class AnimationStoreTest {

    @TempDir
    Path dir;

    private final List<String> logged = new ArrayList<>();

    private AnimationStore store() {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        AnimationStore store = new AnimationStore(dir.resolve("animations"),
                name -> AnimationStoreTest.class.getClassLoader().getResourceAsStream(name), log);
        store.reload();
        return store;
    }

    private static float at(Animation a, int tick, Channel c) {
        float[] out = new float[Channel.COUNT];
        a.sample(tick, out);
        return out[c.ordinal()];
    }

    @Test
    void everyPresetShipsReadsCleanlyAndEndsAtRest() throws IOException {
        AnimationStore store = store();
        assertEquals(List.of(), logged);
        // The jar's folder holds exactly the presets the store knows.
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", "animations"))) {
            assertEquals(new TreeSet<>(AnimationStore.PRESETS),
                    new TreeSet<>(files.map(f -> f.getFileName().toString().replace(".yml", "")).toList()));
        }
        Map<String, Integer> lengths = Map.ofEntries(Map.entry("greet", 48), Map.entry("wave", 48), Map.entry("talk", 40),
                Map.entry("beckon", 36), Map.entry("stretch", 40), Map.entry("look_around", 60), Map.entry("nod", 20),
                Map.entry("head_shake", 24), Map.entry("shrug", 24), Map.entry("scratch_head", 40), Map.entry("clap", 36),
                Map.entry("foot_tap", 32), Map.entry("look_up", 44), Map.entry("hop", 10), Map.entry("bow", 34), Map.entry("cheer", 40), Map.entry("point", 40));
        List<String> glances = List.of("look_around", "head_shake", "look_up");
        for (String id : AnimationStore.PRESETS) {
            Animation a = store.get(id);
            assertNotNull(a, id);
            assertSame(a, store.preset(id));
            assertTrue(a.builtin(), id);
            assertTrue(a.endsAtRest(), id + " ends at rest");
            assertFalse(a.loop(), id);
            assertEquals(lengths.get(id), a.length(), id + " length");
            assertEquals(glances.contains(id), a.glance(), id + " glance");
            assertEquals(0, at(a, 0, Channel.RIGHT_ARM_FORWARD), id + " starts at rest");
        }
        for (String id : AnimationStore.DEFAULT_IDLE) assertNotNull(store.get(id), "idle " + id);
        assertEquals(AnimationStore.PRESETS.size(), store.all().size());
    }

    @Test
    void presetsHoldTheDesignValues() {
        AnimationStore store = store();
        Animation greet = store.get("greet"), wave = store.get("wave");
        assertEquals(3.5f, at(greet, 4, Channel.ROOT_Y), 1e-6f);
        assertEquals(Easing.EASE_OUT, greet.keyframes().get(1).ease());
        assertEquals(90, at(greet, 4, Channel.RIGHT_ARM_FORWARD), 1e-6f);
        assertEquals(166, at(greet, 12, Channel.RIGHT_ARM_FORWARD), 1e-6f);
        assertEquals(-26, at(greet, 12, Channel.RIGHT_ARM_OUT), 1e-6f);
        assertEquals(-6, at(greet, 32, Channel.RIGHT_ARM_OUT), 1e-6f);
        assertEquals(Easing.EASE_IN_OUT, greet.keyframes().getLast().ease());
        // A wave is the greeting without the hop.
        for (int t = 0; t <= 48; t++) {
            assertEquals(0, at(wave, t, Channel.ROOT_Y), "wave hop at " + t);
            assertEquals(at(greet, t, Channel.RIGHT_ARM_FORWARD), at(wave, t, Channel.RIGHT_ARM_FORWARD), 1e-6f, "wave at " + t);
        }
        assertEquals(3, at(store.get("talk"), 4, Channel.ROOT_Y), 1e-6f);
        assertEquals(145, at(store.get("beckon"), 16, Channel.RIGHT_ARM_FORWARD), 1e-6f);
        assertEquals(-45, at(store.get("look_around"), 24, Channel.HEAD_YAW), 1e-6f);
        assertEquals(14, at(store.get("foot_tap"), 12, Channel.RIGHT_LEG_FORWARD), 1e-6f);
        assertEquals(4, at(store.get("hop"), 4, Channel.ROOT_Y), 1e-6f);
        assertEquals(1, at(store.get("stretch"), 20, Channel.SHOULDERS), 1e-6f);
    }

    @Test
    void savedAnimationsReplacePresetsUntilDeleted() throws IOException {
        AnimationStore store = store();
        int[] heard = {0};
        store.addListener(() -> heard[0]++);
        Animation shipped = store.preset("wave");
        Animation edited = new Animation("wave", "Big wave", 20, false, false,
                List.of(new Keyframe(0, Easing.LINEAR, null), new Keyframe(10, Easing.EASE_OUT, values(Channel.RIGHT_ARM_FORWARD, 170.5f)),
                        new Keyframe(20, Easing.LINEAR, null)), true);
        store.save(edited);
        assertEquals(1, heard[0]);
        assertTrue(Files.exists(dir.resolve("animations/wave.yml")));
        assertEquals("Big wave", store.get("wave").name());
        assertFalse(store.get("wave").builtin(), "the server's own, not as shipped");
        assertSame(shipped, store.preset("wave"));
        assertTrue(store.saved("wave"));

        // It survives a reload, value for value.
        AnimationStore again = store();
        assertEquals(edited.keyframes(), again.get("wave").keyframes());
        assertEquals(170.5f, at(again.get("wave"), 10, Channel.RIGHT_ARM_FORWARD), 1e-6f);

        assertTrue(store.delete("wave"));
        assertEquals(2, heard[0]);
        assertSame(shipped, store.get("wave"));
        assertFalse(Files.exists(dir.resolve("animations/wave.yml")));
        assertFalse(store.delete("wave"), "nothing left to delete");
        assertFalse(store.delete("Not An Id"));
    }

    @Test
    void ownAnimationsComeAndGo() throws IOException {
        AnimationStore store = store();
        Animation dance = new Animation("dance", "Dance", 30, true, false,
                List.of(new Keyframe(15, Easing.EASE_IN_OUT, values(Channel.ROOT_YAW, 180))), false);
        store.save(dance);
        assertNotNull(store.get("dance"));
        assertNull(store.preset("dance"));
        String text = Files.readString(dir.resolve("animations/dance.yml"));
        assertTrue(text.contains("root-yaw: 180\n"), text);
        assertTrue(text.contains("  - at: 15\n    ease: ease-in-out\n"), text);
        assertTrue(store.delete("dance"));
        assertNull(store.get("dance"));
        assertThrows(IllegalArgumentException.class, () -> store.save(dance.withId("none", "None")));
    }

    @Test
    void brokenFilesAreLoggedWithTheirNameAndLeftOut() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("animations"));
        Files.writeString(folder.resolve("broken.yml"), "name: [unclosed\n", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("nokeys.yml"), "name: x\nlength: 4\n", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("typo.yml"), "name: Typo\nlenght: 4\nkeyframes:\n  - at: 0\n  - at: 4\n    right-arm-forwrd: 9\n",
                StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("Bad Name.yml"), "keyframes: []\n", StandardCharsets.UTF_8);
        AnimationStore store = store();
        assertNull(store.get("broken"));
        assertNull(store.get("nokeys"));
        assertNotNull(store.get("typo"), "typos are reported, the rest is used");
        assertTrue(logged.stream().anyMatch(m -> m.startsWith("animations/broken.yml: is not valid YAML")), logged.toString());
        assertTrue(logged.stream().anyMatch(m -> m.startsWith("animations/nokeys.yml: nokeys: no keyframes list")), logged.toString());
        assertTrue(logged.stream().anyMatch(m -> m.contains("animations/typo.yml: unknown key 'lenght'")), logged.toString());
        assertTrue(logged.stream().anyMatch(m -> m.contains("animations/typo.yml: typo keyframe 2: no channel called 'right-arm-forwrd'")),
                logged.toString());
        assertTrue(logged.stream().anyMatch(m -> m.startsWith("animations/Bad Name.yml: the file name must be an animation id")),
                logged.toString());
        assertEquals(AnimationStore.PRESETS.size() + 1, store.all().size());
    }

    private static float[] values(Channel c, float v) {
        float[] out = new float[Channel.COUNT];
        out[c.ordinal()] = v;
        return out;
    }
}
