package dev.livelynpcs.pack;

import dev.livelynpcs.skin.Json;
import dev.livelynpcs.skin.SkinImage;
import dev.livelynpcs.skin.TestSkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pack zip: what is in it, and the same bytes (and SHA-1) for the same skins, every time. */
class PackBuilderTest {

    private static final String A = "aaaaaaaaaaaaaaa1", B = "0000000000000002", C = "ccccccccccccccc3";

    private static List<PackBuilder.SkinInput> skins() {
        return List.of(new PackBuilder.SkinInput(A, false, TestSkins.unique(64, 64, 31)),
                new PackBuilder.SkinInput(B, true, TestSkins.unique(64, 64, 32)),
                new PackBuilder.SkinInput(C, false, TestSkins.unique(64, 32, 33)));
    }

    private static List<ZipEntry> entries(byte[] zip) throws IOException {
        List<ZipEntry> out = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.add(e);
        }
        return out;
    }

    @Test
    void theSameSkinsGiveTheSameBytesInAnyOrderAndAnyTimeZone() {
        PackBuilder.Pack first = PackBuilder.build(skins());
        List<PackBuilder.SkinInput> reversed = new ArrayList<>(skins());
        java.util.Collections.reverse(reversed);
        TimeZone zone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            PackBuilder.Pack second = PackBuilder.build(reversed);
            assertArrayEquals(first.zip(), second.zip());
            assertEquals(first.sha1(), second.sha1());
        } finally {
            TimeZone.setDefault(zone);
        }
        assertEquals(SkinImage.sha1(first.zip()), first.sha1());
        assertTrue(first.sha1().matches("[0-9a-f]{40}"));
        // A different skin, a different pack.
        List<PackBuilder.SkinInput> other = new ArrayList<>(skins());
        other.set(0, new PackBuilder.SkinInput(A, true, TestSkins.unique(64, 64, 31)));
        assertNotEquals(first.sha1(), PackBuilder.build(other).sha1());
    }

    @Test
    void entriesAreSortedWithOneFixedTime() throws IOException {
        List<ZipEntry> entries = entries(PackBuilder.build(skins()).zip());
        List<String> names = entries.stream().map(ZipEntry::getName).toList();
        List<String> sorted = new ArrayList<>(names);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, names);
        for (ZipEntry e : entries) {
            assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), e.getTimeLocal(), e.getName());
            assertFalse(e.isDirectory());
        }
        // pack.mcmeta, 2 atlases, and per skin a texture, 6 items and 6 models
        assertEquals(3 + 3 * 13, names.size());
    }

    @Test
    void contentsAreAsDesigned() {
        PackBuilder.Pack pack = PackBuilder.build(skins());
        assertEquals(Set.of(A, B, C), pack.keys());
        Map<String, byte[]> files = pack.files();
        assertEquals(Json.parse("{\"pack\":{\"pack_format\":46,\"supported_formats\":[46,97],\"min_format\":46,\"max_format\":[97,1],"
                + "\"description\":\"Lively NPCs\"}}"), json(files.get("pack.mcmeta")));
        Object atlas = Json.parse("{\"sources\":[{\"type\":\"directory\",\"source\":\"livelynpcs\",\"prefix\":\"livelynpcs/\"}]}");
        assertEquals(atlas, json(files.get("assets/minecraft/atlases/items.json")));
        assertEquals(atlas, json(files.get("assets/minecraft/atlases/blocks.json")));
        for (String key : List.of(A, B, C)) {
            for (String part : ModelBuilder.parts()) {
                assertEquals(Json.parse(Json.write(ModelBuilder.itemDefinition(key, part))),
                        json(files.get("assets/livelynpcs/items/" + key + "/" + part + ".json")));
                assertEquals(Json.parse(Json.write(ModelBuilder.partModel(key, part, key.equals(B)))),
                        json(files.get("assets/livelynpcs/models/npc/" + key + "/" + part + ".json")));
            }
        }
        // The texture: 64x64 RGBA, an old skin upgraded, base layers opaque.
        SkinImage.Pixels texture = assertDoesNotThrow(() -> SkinImage.read(files.get("assets/livelynpcs/textures/livelynpcs/" + C + ".png")));
        assertEquals(64, texture.height());
        assertArrayEquals(SkinImage.asTheGameDraws(TestSkins.unique(64, 32, 33)).argb(), texture.argb());
    }

    private static <T> T assertDoesNotThrow(java.util.concurrent.Callable<T> call) {
        try {
            return call.call();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Object json(byte[] bytes) {
        return Json.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void noSkinsIsAnEmptyButValidPack() {
        PackBuilder.Pack pack = PackBuilder.build(List.of());
        assertTrue(pack.keys().isEmpty());
        assertEquals(Set.of("pack.mcmeta", "assets/minecraft/atlases/items.json", "assets/minecraft/atlases/blocks.json"),
                pack.files().keySet());
    }

    @Test
    void badKeysAndSizesAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PackBuilder.build(List.of(new PackBuilder.SkinInput("NOT-A-KEY", false, TestSkins.unique(64, 64, 1)))));
        assertThrows(IllegalArgumentException.class,
                () -> PackBuilder.build(List.of(new PackBuilder.SkinInput(A, false, TestSkins.unique(32, 32, 1)))));
    }

    @Test
    void aBuiltPackReadsBackWithItsSkins() throws IOException {
        PackBuilder.Pack pack = PackBuilder.build(skins());
        PackBuilder.Pack read = PackBuilder.read(pack.zip());
        assertEquals(pack.keys(), read.keys());
        assertEquals(pack.sha1(), read.sha1());
        assertEquals(pack.files().keySet(), read.files().keySet());
        assertThrows(IOException.class, () -> PackBuilder.read("not a zip".getBytes()));
        assertThrows(IOException.class, () -> PackBuilder.read(pack.assetsZip()));
    }

    @Test
    void theAssetsZipIsThePackWithoutItsMcmeta() throws IOException {
        PackBuilder.Pack pack = PackBuilder.build(skins());
        List<String> names = entries(pack.assetsZip()).stream().map(ZipEntry::getName).toList();
        assertFalse(names.contains("pack.mcmeta"));
        assertEquals(pack.files().size() - 1, names.size());
        assertArrayEquals(pack.assetsZip(), pack.assetsZip());
    }

    @Test
    void writingAssetsReplacesOurFolderAndJoinsAtlases(@TempDir Path dir) throws IOException {
        // Another pack's files are there already: its own atlas source and its own namespace.
        Path theirAtlas = dir.resolve("assets/minecraft/atlases/items.json");
        Files.createDirectories(theirAtlas.getParent());
        Files.writeString(theirAtlas, "{\"sources\": [{\"type\": \"minecraft:directory\", \"source\": \"npc\", \"prefix\": \"npc/\"}]}");
        Path theirs = dir.resolve("assets/raiseamob/models/x.json");
        Files.createDirectories(theirs.getParent());
        Files.writeString(theirs, "{}");
        Path stale = dir.resolve("assets/livelynpcs/models/npc/ffffffffffffffff/head.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{}");

        PackBuilder.Pack pack = PackBuilder.build(skins());
        PackBuilder.writeAssets(dir, pack);
        PackBuilder.writeAssets(dir, pack); // twice: nothing doubles

        assertTrue(Files.exists(theirs), "other namespaces are left alone");
        assertFalse(Files.exists(stale), "skins no longer in the pack go");
        assertFalse(Files.exists(dir.resolve("pack.mcmeta")));
        assertArrayEquals(pack.files().get("assets/livelynpcs/textures/livelynpcs/" + A + ".png"),
                Files.readAllBytes(dir.resolve("assets/livelynpcs/textures/livelynpcs/" + A + ".png")));
        assertEquals(Json.parse("{\"sources\":[{\"type\":\"minecraft:directory\",\"source\":\"npc\",\"prefix\":\"npc/\"},"
                + "{\"type\":\"directory\",\"source\":\"livelynpcs\",\"prefix\":\"livelynpcs/\"}]}"), json(Files.readAllBytes(theirAtlas)));
        assertEquals(json(pack.files().get(PackBuilder.BLOCKS_ATLAS)),
                json(Files.readAllBytes(dir.resolve("assets/minecraft/atlases/blocks.json"))));

        Files.writeString(theirAtlas, "not json");
        assertThrows(IOException.class, () -> PackBuilder.writeAssets(dir, pack));
    }
}
