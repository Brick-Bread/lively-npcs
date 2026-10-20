package dev.livelynpcs.skin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

/** Synthetic skins for tests: every pixel a different colour, so any wrong uv shows. */
public final class TestSkins {

    private TestSkins() {
    }

    /**
     * A skin of this size whose every pixel has its own colour (red = 4x, green = 4y, blue from a seed),
     * with random alpha everywhere (some fully see-through), so opaque-making and outer layers show too.
     */
    public static SkinImage.Pixels unique(int width, int height, long seed) {
        Random random = new Random(seed);
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = switch (random.nextInt(4)) {
                    case 0 -> 0;
                    case 1 -> 255;
                    default -> 1 + random.nextInt(254);
                };
                argb[y * width + x] = alpha << 24 | (x * 4) << 16 | (y * 4) << 8 | random.nextInt(256);
            }
        }
        return new SkinImage.Pixels(width, height, argb);
    }

    /** A signed-property value naming this texture, as Mojang writes it. */
    public static String value(String texture, boolean slim) {
        String skin = "{\"url\" : \"http://textures.minecraft.net/texture/" + texture + "\""
                + (slim ? ", \"metadata\" : { \"model\" : \"slim\" }" : "") + "}";
        String json = "{\n  \"timestamp\" : 1760900000000,\n  \"profileId\" : \"069a79f444e94726a5befca90e38aaf5\",\n"
                + "  \"profileName\" : \"Notch\",\n  \"signatureRequired\" : true,\n  \"textures\" : {\n    \"SKIN\" : " + skin
                + ",\n    \"CAPE\" : {\"url\" : \"http://textures.minecraft.net/texture/2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933\"}\n  }\n}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A made-up signature (base64, as Mojang's are; nothing here checks it against Mojang's key). */
    public static String signature(String texture) {
        return Base64.getEncoder().encodeToString(("signed " + texture).getBytes(StandardCharsets.UTF_8));
    }

    /** The texture id Mojang would give this file: its SHA-256 without leading zeros. */
    public static String texture(byte[] png) {
        String hex = SkinImage.sha256(png);
        int i = 0;
        while (i < hex.length() - 1 && hex.charAt(i) == '0') i++;
        return hex.substring(i);
    }

    /** A Skin for this PNG file. */
    public static Skin skin(byte[] png, boolean slim, String source) {
        String texture = texture(png);
        return new Skin(texture.substring(0, 16), texture, value(texture, slim), signature(texture), slim, source);
    }
}
