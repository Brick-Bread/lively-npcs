package dev.livelynpcs.skin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a signed "textures" property says: the skin texture it points at and whether the skin has slim
 * arms. The value is base64 JSON like
 * {@code {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/<id>","metadata":{"model":"slim"}}}}};
 * the texture id is the last part of that link (Mojang makes it from the image's SHA-256, without
 * leading zeros), and a skin's key is its first 16 characters. JDK only (ExportCli uses it).
 *
 * @param texture the texture id, lowercase hex
 * @param slim    metadata.model is "slim"
 */
public record TextureValue(String texture, boolean slim) {

    /** A texture id: lowercase hex, at least long enough for a key. */
    public static final Pattern TEXTURE = Pattern.compile("[0-9a-f]{16,64}");

    /** The skin key for this texture: its first 16 characters. */
    public String key() {
        return key(texture);
    }

    public static String key(String texture) {
        return texture.substring(0, 16);
    }

    /**
     * Reads a signed property's value. Throws IllegalArgumentException, with a message for people, when
     * it is not base64 JSON naming a skin on textures.minecraft.net.
     */
    public static TextureValue parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("the skin value is empty");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the skin value is not base64 (" + e.getMessage() + ")");
        }
        Map<String, Object> json;
        try {
            json = Json.object(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the skin value is not a textures property (" + e.getMessage() + ")");
        }
        Object skin = Json.at(json, "textures", "SKIN");
        if (skin == null) {
            throw new IllegalArgumentException("the skin value has no skin in it (a player with one of the game's default skins?)");
        }
        String url = Json.string(skin, "url");
        if (url == null) throw new IllegalArgumentException("the skin value names no texture link");
        String texture = textureOf(url);
        boolean slim = "slim".equals(Json.string(skin, "metadata", "model"));
        return new TextureValue(texture, slim);
    }

    /** Whether the value reads fine but names no skin (a player wearing one of the game's default skins). */
    public static boolean withoutSkin(String value) {
        try {
            Map<String, Object> json = Json.object(new String(Base64.getDecoder().decode(value.replaceAll("\\s", "")),
                    StandardCharsets.UTF_8));
            return Json.at(json, "textures") instanceof Map && Json.at(json, "textures", "SKIN") == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The texture id in a textures.minecraft.net link; IllegalArgumentException for any other link. */
    public static String textureOf(String url) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("'" + url + "' is not a texture link");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!(scheme.equals("http") || scheme.equals("https")) || !host.equals("textures.minecraft.net")
                || !path.startsWith("/texture/")) {
            throw new IllegalArgumentException("'" + url + "' is not a Mojang skin texture link (textures.minecraft.net)");
        }
        String texture = path.substring("/texture/".length()).toLowerCase(Locale.ROOT);
        if (!TEXTURE.matcher(texture).matches()) {
            throw new IllegalArgumentException("'" + texture + "' is not a texture id (16 to 64 hex characters)");
        }
        return texture;
    }
}
