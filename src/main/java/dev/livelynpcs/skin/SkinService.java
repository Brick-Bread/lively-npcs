package dev.livelynpcs.skin;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * The skins NPCs wear, kept in plugins/LivelyNPCs/skins.yml (and their images in skins/&lt;texture&gt;.png).
 * The get/all calls are main-thread; the from* calls do their web requests off the main thread and
 * complete on the main thread, with the skin stored, or exceptionally with a message for people
 * (no such player, MineSkin key missing, not a skin image, ...).
 */
public interface SkinService {

    /** The skin with this key, or null. */
    Skin get(String key);

    /** Every stored skin. */
    Collection<Skin> all();

    /** That player's current skin, signed by Mojang (no key needed). */
    CompletableFuture<Skin> fromPlayer(String name);

    /** A skin image at a web link, signed through MineSkin (skins.mineskin-key). */
    CompletableFuture<Skin> fromUrl(String url, boolean slim);

    /** An uploaded skin image (a 64x64 or 64x32 PNG), signed through MineSkin (skins.mineskin-key). */
    CompletableFuture<Skin> fromUpload(byte[] png, boolean slim);

    /**
     * A signed property someone already has (from another plugin's file, say): checked, stored and
     * returned. Throws IllegalArgumentException when the value is not a texture property.
     */
    Skin fromSigned(String value, String signature, String source);

    /**
     * The skin's image (64x64 RGBA PNG bytes; an old 64x32 skin is converted the game's way), cached
     * in skins/; downloaded from textures.minecraft.net the first time (off the main thread: call this
     * from an async task or the web server's threads).
     */
    byte[] png(Skin skin) throws IOException;

    /** Re-reads skins.yml (after /npc reload). */
    void reload();
}
