package dev.livelynpcs.skin;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Skin pictures the way the game prepares them (SkinTextureDownloader.processLegacySkin): a 64x32
 * skin is given the 64x64 layout, an old skin's hat with no see-through pixel is dropped, and the base
 * layers (head, body, arms, legs) are made opaque. A port of RaiseAMob's npc_models.py
 * (as_the_game_draws). Also reads and writes PNGs (written the same way every time, so a pack built
 * from the same skins has the same SHA-1) and checks a download against its texture id. JDK only.
 */
public final class SkinImage {

    /** A picture as ARGB ints (0xAARRGGBB, not premultiplied), row by row. */
    public record Pixels(int width, int height, int[] argb) {
        public Pixels {
            if (argb.length != width * height) throw new IllegalArgumentException("pixel count does not match the size");
        }

        public int get(int x, int y) {
            return argb[y * width + x];
        }

        public void set(int x, int y, int color) {
            argb[y * width + x] = color;
        }

        public Pixels copy() {
            return new Pixels(width, height, argb.clone());
        }
    }

    /**
     * The game's upgrade of an old 64x32 skin: each (x, y, dx, dy, w, h) copies a piece of the right arm
     * or leg, mirrored, (dx, dy) along to where the left arm or leg goes. The left limbs get no outer layer.
     */
    static final int[][] LEGACY = {
            {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
            {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
            {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
    /** The base layers (head, body, arms and legs) as {left, top, right, bottom}, which the game always draws opaque. */
    static final int[][] BASE_AREAS = {{0, 0, 32, 16}, {0, 16, 64, 32}, {16, 48, 48, 64}};

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private SkinImage() {
    }

    /**
     * A skin the way the game draws it: 64x64, a 64x32 skin upgraded, the base layers opaque. Throws
     * IllegalArgumentException for any other size (the game discards those).
     */
    public static Pixels asTheGameDraws(Pixels skin) {
        Pixels out;
        if (skin.width() == 64 && skin.height() == 32) {
            out = new Pixels(64, 64, new int[64 * 64]);
            System.arraycopy(skin.argb(), 0, out.argb(), 0, 64 * 32);
            for (int[] c : LEGACY) {
                int x = c[0], y = c[1], dx = c[2], dy = c[3], w = c[4], h = c[5];
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < w; i++) {
                        out.set(x + dx + i, y + dy + j, out.get(x + w - 1 - i, y + j));
                    }
                }
            }
            notchTransparencyHack(out, 32, 0, 64, 32);
        } else if (skin.width() == 64 && skin.height() == 64) {
            out = skin.copy();
        } else {
            throw new IllegalArgumentException("a skin is " + skin.width() + "x" + skin.height() + ", not 64x64 or 64x32");
        }
        for (int[] area : BASE_AREAS) {
            for (int y = area[1]; y < area[3]; y++) {
                for (int x = area[0]; x < area[2]; x++) out.set(x, y, out.get(x, y) | 0xFF000000);
            }
        }
        return out;
    }

    /** An old skin's hat area with no see-through pixel (alpha below 128) at all is made fully see-through. */
    private static void notchTransparencyHack(Pixels image, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if ((image.get(x, y) >>> 24) < 128) return;
            }
        }
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) image.set(x, y, image.get(x, y) & 0x00FFFFFF);
        }
    }

    /**
     * Reads a PNG (any colour type) into ARGB, the way the game's image reader does: grey and 16-bit
     * images are taken by their stored values, not through a colour-space conversion.
     */
    public static Pixels read(byte[] png) throws IOException {
        if (png.length < 8 || !startsWith(png, PNG_SIGNATURE)) throw new IOException("not a PNG image");
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (RuntimeException e) {
            throw new IOException("not a readable PNG image (" + e.getMessage() + ")", e);
        }
        if (image == null) throw new IOException("not a readable PNG image");
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = new int[w * h];
        ColorModel model = image.getColorModel();
        Raster raster = image.getRaster();
        int bands = raster.getNumBands();
        boolean grey = model.getColorSpace().getType() == ColorSpace.TYPE_GRAY && model.getNumColorComponents() == 1
                && !(model instanceof java.awt.image.IndexColorModel);
        boolean wide = raster.getTransferType() == DataBuffer.TYPE_USHORT && !(model instanceof java.awt.image.IndexColorModel);
        if (grey || (wide && bands >= 3)) {
            int bits = model.getComponentSize(0);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int r, g, b, a = 255;
                    if (grey) {
                        r = g = b = to8(raster.getSample(x, y, 0), bits);
                        if (bands > 1) a = to8(raster.getSample(x, y, 1), model.getComponentSize(1));
                    } else {
                        r = to8(raster.getSample(x, y, 0), bits);
                        g = to8(raster.getSample(x, y, 1), model.getComponentSize(1));
                        b = to8(raster.getSample(x, y, 2), model.getComponentSize(2));
                        if (bands > 3) a = to8(raster.getSample(x, y, 3), model.getComponentSize(3));
                    }
                    argb[y * w + x] = a << 24 | r << 16 | g << 8 | b;
                }
            }
        } else {
            image.getRGB(0, 0, w, h, argb, 0, w);
        }
        return new Pixels(w, h, argb);
    }

    /** A stored sample of this many bits as 0..255: 16 bits keep their high byte, fewer are scaled up. */
    private static int to8(int sample, int bits) {
        if (bits == 8) return sample & 0xFF;
        if (bits > 8) return (sample >>> (bits - 8)) & 0xFF;
        int max = (1 << bits) - 1;
        return max == 0 ? 0 : sample * 255 / max;
    }

    /**
     * Writes an 8-bit RGBA PNG with no extra chunks and the same compression every time, so the same
     * pixels always give the same bytes (on the same Java).
     */
    public static byte[] png(Pixels image) {
        int w = image.width(), h = image.height();
        byte[] raw = new byte[h * (1 + w * 4)];
        int p = 0;
        for (int y = 0; y < h; y++) {
            raw[p++] = 0; // filter: none
            for (int x = 0; x < w; x++) {
                int c = image.get(x, y);
                raw[p++] = (byte) (c >>> 16);
                raw[p++] = (byte) (c >>> 8);
                raw[p++] = (byte) c;
                raw[p++] = (byte) (c >>> 24);
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                compressed.write(buffer, 0, n);
            }
        } finally {
            deflater.end();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PNG_SIGNATURE);
        byte[] header = new byte[13];
        putInt(header, 0, w);
        putInt(header, 4, h);
        header[8] = 8; // bits per sample
        header[9] = 6; // RGBA
        chunk(out, "IHDR", header);
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.writeBytes(length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] sum = new byte[4];
        putInt(sum, 0, (int) crc.getValue());
        out.writeBytes(sum);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    /** SHA-256 as 64 lowercase hex characters. */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-1 as 40 lowercase hex characters (what a resource pack offer carries). */
    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether a downloaded file is the texture with this id: Mojang names a texture after the SHA-256
     * of its file, written without leading zeros (so compare them as numbers, as npc_models.py does).
     */
    public static boolean matchesTexture(byte[] file, String texture) {
        return stripZeros(sha256(file)).equals(stripZeros(texture.toLowerCase(java.util.Locale.ROOT)));
    }

    private static String stripZeros(String hex) {
        int i = 0;
        while (i < hex.length() - 1 && hex.charAt(i) == '0') i++;
        return hex.substring(i);
    }
}
