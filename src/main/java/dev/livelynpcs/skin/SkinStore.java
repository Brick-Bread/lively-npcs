package dev.livelynpcs.skin;

import dev.livelynpcs.LivelyNpcs;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** skins.yml, Mojang and MineSkin lookups, skin images. OWNER: the pack/skins/web agent. Stub until built. */
public final class SkinStore implements SkinService {

    private final LivelyNpcs plugin;

    public SkinStore(LivelyNpcs plugin) {
        this.plugin = plugin;
    }

    @Override public Skin get(String key) { return null; }
    @Override public Collection<Skin> all() { return List.of(); }
    @Override public CompletableFuture<Skin> fromPlayer(String name) { return CompletableFuture.failedFuture(new IllegalStateException("not built yet")); }
    @Override public CompletableFuture<Skin> fromUrl(String url, boolean slim) { return CompletableFuture.failedFuture(new IllegalStateException("not built yet")); }
    @Override public CompletableFuture<Skin> fromUpload(byte[] png, boolean slim) { return CompletableFuture.failedFuture(new IllegalStateException("not built yet")); }
    @Override public Skin fromSigned(String value, String signature, String source) { throw new IllegalArgumentException("not built yet"); }
    @Override public byte[] png(Skin skin) throws IOException { throw new IOException("not built yet"); }
    @Override public void reload() { }
}
