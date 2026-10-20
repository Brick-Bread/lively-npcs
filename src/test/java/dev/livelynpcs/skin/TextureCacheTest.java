package dev.livelynpcs.skin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Textures are downloaded once, checked against their id, and given back the way the game draws them. */
class TextureCacheTest {

    @TempDir
    Path dir;

    @Test
    void downloadsOnceAndThenReadsTheFile() throws IOException {
        byte[] png = SkinImage.png(TestSkins.unique(64, 64, 11));
        String texture = TestSkins.texture(png);
        AtomicInteger fetches = new AtomicInteger();
        TextureCache cache = new TextureCache(dir, t -> {
            fetches.incrementAndGet();
            assertEquals(texture, t);
            return png;
        });
        assertArrayEquals(png, cache.raw(texture));
        assertTrue(Files.isRegularFile(dir.resolve(texture + ".png")));
        // A new cache over the same folder (a restart) does not download again.
        TextureCache again = new TextureCache(dir, t -> {
            throw new IOException("no network in tests");
        });
        assertArrayEquals(png, again.raw(texture));
        assertEquals(1, fetches.get());
        SkinImage.Pixels drawn = again.image(texture);
        assertEquals(64, drawn.width());
        assertEquals(255, drawn.get(8, 8) >>> 24, "base layer opaque");
        assertArrayEquals(SkinImage.png(SkinImage.asTheGameDraws(TestSkins.unique(64, 64, 11))), again.png(texture));
    }

    @Test
    void aDamagedFileIsFetchedAgainAndAWrongDownloadRefused() throws IOException {
        byte[] png = SkinImage.png(TestSkins.unique(64, 32, 12));
        String texture = TestSkins.texture(png);
        Files.write(dir.resolve(texture + ".png"), new byte[]{1, 2, 3});
        TextureCache cache = new TextureCache(dir, t -> png);
        assertArrayEquals(png, cache.raw(texture));
        assertArrayEquals(png, Files.readAllBytes(dir.resolve(texture + ".png")));
        assertEquals(64, cache.image(texture).height(), "a 64x32 skin comes back 64x64");

        TextureCache wrong = new TextureCache(dir.resolve("other"), t -> "not the texture".getBytes());
        IOException e = assertThrows(IOException.class, () -> wrong.raw(texture));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
        assertTrue(Files.notExists(dir.resolve("other").resolve(texture + ".png")));
        assertThrows(IOException.class, () -> wrong.raw("../../etc/passwd"));
    }
}
