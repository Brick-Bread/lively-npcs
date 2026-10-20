package dev.livelynpcs.skin;

import java.util.regex.Pattern;

/**
 * A skin an NPC can wear: the signed textures property the game needs for the player-model look,
 * and the texture it points at, from which the pack's body-part models are made.
 *
 * @param key       the first 16 hex characters of {@code texture}: names the skin in npcs.yml, in
 *                  skins.yml and in the pack's model ids (livelynpcs:&lt;key&gt;/&lt;part&gt;). The same skin
 *                  has the same key on every server, so packs built anywhere agree.
 * @param texture   the texture id: the last part of its textures.minecraft.net link, which Mojang
 *                  makes from the image's SHA-256 (lowercase hex).
 * @param value     the signed property's value (base64 JSON naming the texture link and model).
 * @param signature Mojang's signature for value (base64).
 * @param slim      whether the skin has slim (3 pixel) arms.
 * @param source    where it came from, for people: "player:Notch", "url:https://...", "upload", "file".
 */
public record Skin(String key, String texture, String value, String signature, boolean slim, String source) {

    public static final Pattern KEY = Pattern.compile("[0-9a-f]{16}");

    public Skin {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("'" + key + "' is not a skin key (16 hex)");
        if (texture == null || !texture.startsWith(key)) throw new IllegalArgumentException("texture " + texture + " does not start with key " + key);
        if (value == null || value.isEmpty() || signature == null || signature.isEmpty()) {
            throw new IllegalArgumentException("skin " + key + " needs a signed value");
        }
        if (source == null) source = "";
    }

    /** The texture image's link, as the game downloads it. */
    public String textureUrl() {
        return "https://textures.minecraft.net/texture/" + texture;
    }
}
