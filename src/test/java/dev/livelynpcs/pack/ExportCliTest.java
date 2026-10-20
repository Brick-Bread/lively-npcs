package dev.livelynpcs.pack;

import dev.livelynpcs.skin.Skin;
import dev.livelynpcs.skin.SkinFile;
import dev.livelynpcs.skin.SkinImage;
import dev.livelynpcs.skin.TestSkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command-line export, on a skins.yml whose skin images are already in the cache (no network). */
class ExportCliTest {

    @TempDir
    Path dir;

    /** Three skins (classic, slim, old 64x32) in skins.yml, their files in the cache. Returns what the pack should be. */
    private PackBuilder.Pack setUp(Path skinsYml, Path cache) throws IOException {
        List<Skin> skins = new ArrayList<>();
        List<PackBuilder.SkinInput> inputs = new ArrayList<>();
        Object[][] made = {{TestSkins.unique(64, 64, 41), false}, {TestSkins.unique(64, 64, 42), true}, {TestSkins.unique(64, 32, 43), false}};
        Files.createDirectories(cache);
        for (Object[] m : made) {
            SkinImage.Pixels image = (SkinImage.Pixels) m[0];
            boolean slim = (Boolean) m[1];
            byte[] png = SkinImage.png(image);
            Skin skin = TestSkins.skin(png, slim, "upload");
            Files.write(cache.resolve(skin.texture() + ".png"), png);
            skins.add(skin);
            inputs.add(new PackBuilder.SkinInput(skin.key(), slim, image));
        }
        Files.createDirectories(skinsYml.getParent());
        Files.writeString(skinsYml, SkinFile.write(skins), StandardCharsets.UTF_8);
        return PackBuilder.build(inputs);
    }

    private record Ran(int code, String out, String err) {
    }

    private static Ran run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int code = ExportCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void writesWhatMergedModeWritesAndTheWholePack() throws IOException {
        Path skinsYml = dir.resolve("LivelyNPCs").resolve("skins.yml");
        Path cache = dir.resolve("cache");
        PackBuilder.Pack expected = setUp(skinsYml, cache);
        Path out = dir.resolve("out");
        Ran ran = run("--skins", skinsYml.toString(), "--out", out.toString(), "--cache", cache.toString(), "--zip");
        assertEquals(0, ran.code(), ran.err());
        assertTrue(ran.out().contains("SHA-1 " + expected.sha1()), ran.out());

        for (String key : expected.keys()) {
            for (String part : ModelBuilder.parts()) {
                assertTrue(Files.isRegularFile(out.resolve("assets/livelynpcs/items/" + key + "/" + part + ".json")));
                assertTrue(Files.isRegularFile(out.resolve("assets/livelynpcs/models/npc/" + key + "/" + part + ".json")));
            }
            assertArrayEquals(expected.files().get("assets/livelynpcs/textures/livelynpcs/" + key + ".png"),
                    Files.readAllBytes(out.resolve("assets/livelynpcs/textures/livelynpcs/" + key + ".png")));
        }
        assertTrue(Files.isRegularFile(out.resolve("assets/minecraft/atlases/items.json")));
        assertTrue(Files.isRegularFile(out.resolve("assets/minecraft/atlases/blocks.json")));
        assertFalse(Files.exists(out.resolve("pack.mcmeta")), "assets only, as merged mode's export/");
        assertArrayEquals(expected.assetsZip(), Files.readAllBytes(out.resolve("lively-npcs-assets.zip")));
        assertArrayEquals(expected.zip(), Files.readAllBytes(out.resolve("lively-npcs-pack.zip")));
    }

    @Test
    void theCacheDefaultsToSkinsBesideSkinsYml() throws IOException {
        Path skinsYml = dir.resolve("plugins").resolve("LivelyNPCs").resolve("skins.yml");
        PackBuilder.Pack expected = setUp(skinsYml, skinsYml.getParent().resolve("skins"));
        Path out = dir.resolve("export");
        Ran ran = run("--skins", skinsYml.toString(), "--out", out.toString());
        assertEquals(0, ran.code(), ran.err());
        assertFalse(Files.exists(out.resolve("lively-npcs-pack.zip")), "only with --zip");
        assertArrayEquals(expected.assetsZip(), Files.readAllBytes(out.resolve("lively-npcs-assets.zip")));
    }

    @Test
    void failuresExitNonZeroAndSayWhy() throws IOException {
        Ran usage = run("--skins");
        assertEquals(2, usage.code());
        assertTrue(usage.err().contains("Usage"), usage.err());
        assertEquals(2, run("--bogus").code());
        assertEquals(2, run("--out", dir.toString()).code());

        Ran missing = run("--skins", dir.resolve("nope.yml").toString(), "--out", dir.resolve("o").toString());
        assertEquals(1, missing.code());
        assertTrue(missing.err().startsWith("Lively NPCs export failed: there is no file"), missing.err());

        Path broken = dir.resolve("broken.yml");
        Files.writeString(broken, "skins:\n  abc: {value: x}\n");
        Ran bad = run("--skins", broken.toString(), "--out", dir.resolve("o").toString());
        assertEquals(1, bad.code());
        assertTrue(bad.err().contains("line 2"), bad.err());

        // An entry that is not a skin is skipped with a warning; the rest is exported.
        Path skinsYml = dir.resolve("x").resolve("skins.yml");
        setUp(skinsYml, dir.resolve("x").resolve("skins"));
        Files.writeString(skinsYml, Files.readString(skinsYml) + "  junk: 1\n");
        Ran warned = run("--skins", skinsYml.toString(), "--out", dir.resolve("o2").toString());
        assertEquals(0, warned.code(), warned.err());
        assertTrue(warned.out().contains("Warning: skins.yml skins.junk"), warned.out());
    }

    /**
     * The real command, in a fresh Java with only the plugin's own classes on the classpath (no Paper,
     * Adventure, Gson or SnakeYAML), as `java -cp LivelyNPCs.jar dev.livelynpcs.pack.ExportCli` runs.
     */
    @Test
    void runsWithoutTheServersLibraries() throws Exception {
        Path skinsYml = dir.resolve("LivelyNPCs").resolve("skins.yml");
        PackBuilder.Pack expected = setUp(skinsYml, skinsYml.getParent().resolve("skins"));
        Path classes = Path.of(ExportCli.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String java = ProcessHandle.current().info().command().orElse("java");
        Path out = dir.resolve("export");
        Process process = new ProcessBuilder(java, "-cp", classes.toString(), "dev.livelynpcs.pack.ExportCli",
                "--skins", skinsYml.toString(), "--out", out.toString(), "--zip")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "finished");
        assertEquals(0, process.exitValue(), output);
        assertArrayEquals(expected.zip(), Files.readAllBytes(out.resolve("lively-npcs-pack.zip")));
    }
}
