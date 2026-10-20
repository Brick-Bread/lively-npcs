package dev.livelynpcs.skin;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Skins prepared the way the game prepares them, and PNGs written the same every time. */
class SkinImageTest {

    private static boolean inBase(int x, int y) {
        for (int[] a : SkinImage.BASE_AREAS) if (x >= a[0] && x < a[2] && y >= a[1] && y < a[3]) return true;
        return false;
    }

    @Test
    void aModernSkinOnlyGetsItsBaseLayersOpaque() {
        SkinImage.Pixels skin = TestSkins.unique(64, 64, 1);
        SkinImage.Pixels drawn = SkinImage.asTheGameDraws(skin);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int before = skin.get(x, y), after = drawn.get(x, y);
                assertEquals(before & 0xFFFFFF, after & 0xFFFFFF, "colour kept at " + x + "," + y);
                assertEquals(inBase(x, y) ? 255 : before >>> 24, after >>> 24, "alpha at " + x + "," + y);
            }
        }
        assertEquals(skin.get(40, 40), TestSkins.unique(64, 64, 1).get(40, 40), "the input is not changed");
    }

    @Test
    void anOldSkinGetsTheGamesUpgrade() {
        SkinImage.Pixels old = TestSkins.unique(64, 32, 2);
        SkinImage.Pixels drawn = SkinImage.asTheGameDraws(old);
        assertEquals(64, drawn.height());
        // The top half is the old skin (alpha aside); the game's copyRect(srcX, srcY, dx, dy, w, h, mirror x)
        // puts each piece at (srcX + dx, srcY + dy), mirrored.
        int[][] copies = SkinImage.LEGACY;
        for (int[] c : copies) {
            for (int j = 0; j < c[5]; j++) {
                for (int i = 0; i < c[4]; i++) {
                    int source = old.get(c[0] + i, c[1] + j);
                    int target = drawn.get(c[0] + c[2] + (c[4] - 1 - i), c[1] + c[3] + j);
                    assertEquals(source & 0xFFFFFF, target & 0xFFFFFF);
                }
            }
        }
        // The left leg's front (16+4..16+8, 52..64) mirrors the right leg's front (4..8, 20..32).
        assertEquals(old.get(4, 20) & 0xFFFFFF, drawn.get(23, 52) & 0xFFFFFF);
        assertEquals(old.get(7, 31) & 0xFFFFFF, drawn.get(20, 63) & 0xFFFFFF);
        // The left limbs' outer layers (0..16 x 48..64 and 48..64 x 48..64) stay empty.
        assertEquals(0, drawn.get(2, 50));
        assertEquals(0, drawn.get(60, 60));
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) if (inBase(x, y)) assertEquals(255, drawn.get(x, y) >>> 24);
    }

    @Test
    void anOldSkinsSolidHatIsDroppedButAHatWithAHoleIsKept() {
        SkinImage.Pixels solid = TestSkins.unique(64, 32, 3);
        for (int y = 0; y < 32; y++) for (int x = 32; x < 64; x++) solid.set(x, y, solid.get(x, y) | 0xFF000000);
        SkinImage.Pixels drawn = SkinImage.asTheGameDraws(solid);
        assertEquals(0, drawn.get(40, 8) >>> 24, "a hat with no see-through pixel is see-through");
        assertEquals(solid.get(40, 8) & 0xFFFFFF, drawn.get(40, 8) & 0xFFFFFF);
        assertEquals(255, drawn.get(44, 20) >>> 24, "the arm below it is base layer, so opaque again");

        SkinImage.Pixels holed = solid.copy();
        holed.set(63, 15, holed.get(63, 15) & 0x7FFFFFFF); // alpha 127
        assertEquals(255, SkinImage.asTheGameDraws(holed).get(40, 8) >>> 24);
    }

    @Test
    void otherSizesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SkinImage.asTheGameDraws(TestSkins.unique(128, 128, 4)));
        assertThrows(IllegalArgumentException.class, () -> SkinImage.asTheGameDraws(TestSkins.unique(64, 48, 4)));
    }

    @Test
    void pngsRoundTripAndAreWrittenTheSameEveryTime() throws IOException {
        SkinImage.Pixels skin = TestSkins.unique(64, 64, 5);
        byte[] png = SkinImage.png(skin);
        assertArrayEquals(png, SkinImage.png(skin.copy()));
        SkinImage.Pixels back = SkinImage.read(png);
        assertArrayEquals(skin.argb(), back.argb());
        // ImageIO agrees about the pixels.
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertEquals(skin.get(10, 20), image.getRGB(10, 20));
    }

    @Test
    void greyPngsAreReadByTheirStoredValues() throws IOException {
        BufferedImage grey = new BufferedImage(64, 32, BufferedImage.TYPE_BYTE_GRAY);
        grey.getRaster().setSample(5, 6, 0, 100);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(grey, "png", out);
        SkinImage.Pixels read = SkinImage.read(out.toByteArray());
        assertEquals(0xFF646464, read.get(5, 6), "grey 100 stays 100 (no colour-space change)");
        assertEquals(0xFF000000, read.get(0, 0));
    }

    @Test
    void notAPngIsRefused() {
        assertThrows(IOException.class, () -> SkinImage.read("hello".getBytes()));
        assertThrows(IOException.class, () -> SkinImage.read(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0}));
    }

    @Test
    void aDownloadMatchesItsTextureIdWithOrWithoutLeadingZeros() {
        byte[] file = "a skin file".getBytes();
        String sha = SkinImage.sha256(file);
        assertTrue(SkinImage.matchesTexture(file, sha));
        assertTrue(SkinImage.matchesTexture(file, "000" + sha));
        String stripped = sha.replaceFirst("^0+", "");
        assertTrue(SkinImage.matchesTexture(file, stripped));
        assertFalse(SkinImage.matchesTexture("another file".getBytes(), sha));
        // A file whose SHA-256 starts with zeros: Mojang's id leaves them off.
        byte[] zeroLed = null;
        for (int i = 0; zeroLed == null; i++) {
            byte[] candidate = ("candidate " + i).getBytes();
            if (SkinImage.sha256(candidate).startsWith("0")) zeroLed = candidate;
        }
        String full = SkinImage.sha256(zeroLed);
        assertTrue(SkinImage.matchesTexture(zeroLed, full.substring(1)));
        assertEquals(full.replaceFirst("^0+", ""), TestSkins.texture(zeroLed));
    }
}
