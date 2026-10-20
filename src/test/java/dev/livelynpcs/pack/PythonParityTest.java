package dev.livelynpcs.pack;

import dev.livelynpcs.skin.Json;
import dev.livelynpcs.skin.SkinImage;
import dev.livelynpcs.skin.TextureValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Java models against RaiseAMob's Python builder (resourcepack/npc_models.py), on its real skins:
 * for every role in tools/season1/skins.json, every element's from/to and every face's uv and rotation
 * in its raiseamob:npc/&lt;role&gt;/&lt;part&gt; models must be ours exactly, and its texture the same picture.
 * Skipped where that checkout and its build (build/resourcepack) are not on this machine.
 */
class PythonParityTest {

    private static final Path SEASON1 = Path.of("C:\\Users\\Brick\\Documents\\GrowAMob-season1");
    private static final Path SKINS_JSON = SEASON1.resolve("tools").resolve("season1").resolve("skins.json");
    private static final Path CACHE = SEASON1.resolve("build").resolve("resourcepack").resolve("skins");
    private static final Path ZIP = SEASON1.resolve("build").resolve("resourcepack").resolve("raiseamob-pack.zip");

    @Test
    void modelsAndTexturesMatchThePythonPack() throws IOException {
        assumeTrue(Files.isRegularFile(SKINS_JSON) && Files.isDirectory(CACHE) && Files.isRegularFile(ZIP),
                "RaiseAMob's Season 1 pack build is not on this machine");
        Map<String, byte[]> python = unzip(Files.readAllBytes(ZIP));
        Map<String, Object> roles = Json.object(Files.readString(SKINS_JSON, StandardCharsets.UTF_8));
        List<PackBuilder.SkinInput> inputs = new ArrayList<>();
        int checked = 0, legacy = 0, slims = 0;
        for (Map.Entry<String, Object> role : roles.entrySet()) {
            String name = role.getKey();
            String texture = Json.string(role.getValue(), "texture");
            TextureValue parsed = TextureValue.parse(Json.string(role.getValue(), "value"));
            assertEquals(texture, parsed.texture(), name + ": the texture in its signed value");
            String key = parsed.key();
            boolean slim = parsed.slim();
            if (slim) slims++;

            for (String part : ModelBuilder.parts()) {
                String where = name + "/" + part;
                byte[] file = python.get("assets/raiseamob/models/npc/" + name + "/" + part + ".json");
                assertTrue(file != null, where + " is not in the Python pack");
                Map<String, Object> theirs = Json.object(new String(file, StandardCharsets.UTF_8));
                Map<String, Object> ours = Json.object(Json.write(ModelBuilder.partModel(key, part, slim)));
                assertSameGeometry(where, theirs, ours);
                checked++;
            }

            // Read only: that checkout's files are never changed from here.
            byte[] raw = Files.readAllBytes(CACHE.resolve(texture + ".png"));
            assertTrue(SkinImage.matchesTexture(raw, texture), name + ": the cached file is its texture");
            SkinImage.Pixels original = SkinImage.read(raw);
            if (original.height() == 32) legacy++;
            SkinImage.Pixels ourTexture = SkinImage.asTheGameDraws(original);
            SkinImage.Pixels theirTexture = SkinImage.read(python.get("assets/raiseamob/textures/npc/" + name + ".png"));
            assertArrayEquals(theirTexture.argb(), ourTexture.argb(), name + ": the texture as the game draws it");
            inputs.add(new PackBuilder.SkinInput(key, slim, ourTexture));
        }
        assertEquals(roles.size() * 6, checked);
        // The real skins cover the cases that matter.
        assertTrue(legacy > 0, "an old 64x32 skin is among them");
        assertTrue(slims < roles.size(), "a classic skin is among them");
        // Ours build into a pack and pass the pixel check on every real skin.
        assertEquals(roles.size(), PackBuilder.build(inputs).keys().size());
    }

    @SuppressWarnings("unchecked")
    private static void assertSameGeometry(String where, Map<String, Object> theirs, Map<String, Object> ours) {
        assertEquals(theirs.get("ambientocclusion"), ours.get("ambientocclusion"), where + " ambientocclusion");
        List<Object> a = (List<Object>) theirs.get("elements"), b = (List<Object>) ours.get("elements");
        assertEquals(a.size(), b.size(), where + ": number of elements");
        for (int i = 0; i < a.size(); i++) {
            Map<String, Object> ea = (Map<String, Object>) a.get(i), eb = (Map<String, Object>) b.get(i);
            assertEquals(ea.get("from"), eb.get("from"), where + " element " + i + " from");
            assertEquals(ea.get("to"), eb.get("to"), where + " element " + i + " to");
            Map<String, Object> fa = (Map<String, Object>) ea.get("faces"), fb = (Map<String, Object>) eb.get("faces");
            assertEquals(fa.keySet(), fb.keySet(), where + " element " + i + " faces");
            for (String side : fa.keySet()) {
                Map<String, Object> sa = (Map<String, Object>) fa.get(side), sb = (Map<String, Object>) fb.get(side);
                assertEquals(sa.get("uv"), sb.get("uv"), where + " element " + i + " " + side + " uv");
                assertEquals(Json.number(sa, 0, "rotation"), Json.number(sb, 0, "rotation"), where + " element " + i + " " + side + " rotation");
                assertEquals("#skin", sb.get("texture"));
            }
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) if (!e.isDirectory()) files.put(e.getName(), in.readAllBytes());
        }
        return files;
    }
}
