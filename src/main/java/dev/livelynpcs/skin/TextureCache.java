package dev.livelynpcs.skin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin textures on disk: &lt;folder&gt;/&lt;texture&gt;.png, each downloaded once from textures.minecraft.net and
 * checked against its texture id (the SHA-256 of the file), so a damaged or wrong file is never used.
 * {@link #image} gives the picture the way the game draws it (64x64, base layers opaque). Safe to use
 * from many threads; JDK only (ExportCli uses it too).
 */
public final class TextureCache {

    /** Downloads one texture file. */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch(String texture) throws IOException;
    }

    /** Larger than any skin Mojang serves; anything bigger is not a skin. */
    static final int MAX_BYTES = 1024 * 1024;

    private final Path folder;
    private final Fetcher fetcher;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, SkinImage.Pixels> images = new ConcurrentHashMap<>();

    public TextureCache(Path folder, Fetcher fetcher) {
        this.folder = folder;
        this.fetcher = fetcher;
    }

    /** Fetches from textures.minecraft.net over the JDK's HTTP client, saying who asks (userAgent). */
    public static Fetcher web(String userAgent) {
        return texture -> {
            Http.Response response = Http.jdk().get("https://textures.minecraft.net/texture/" + texture,
                    Map.of("User-Agent", userAgent), MAX_BYTES);
            if (response.status() == 404) throw new IOException("textures.minecraft.net has no texture " + texture);
            if (response.status() != 200) {
                throw new IOException("textures.minecraft.net answered HTTP " + response.status() + " for texture " + texture);
            }
            return response.body();
        };
    }

    public Path folder() {
        return folder;
    }

    /** Where the texture's file is kept. */
    public Path file(String texture) {
        return folder.resolve(texture + ".png");
    }

    /**
     * The texture's file as Mojang serves it: from the folder when it is there and checks out, else
     * downloaded (once), checked and saved. IOException (for people) when it cannot be had.
     */
    public byte[] raw(String texture) throws IOException {
        if (!TextureValue.TEXTURE.matcher(texture).matches()) throw new IOException("'" + texture + "' is not a texture id");
        synchronized (locks.computeIfAbsent(texture, t -> new Object())) {
            Path file = file(texture);
            if (Files.isRegularFile(file)) {
                byte[] data = Files.readAllBytes(file);
                if (SkinImage.matchesTexture(data, texture)) return data;
                // Damaged or not this texture: fetch it again.
                Files.deleteIfExists(file);
            }
            byte[] data = fetcher.fetch(texture);
            if (data.length > MAX_BYTES) throw new IOException("texture " + texture + " is too big to be a skin");
            if (!SkinImage.matchesTexture(data, texture)) {
                throw new IOException("the download of texture " + texture + " does not match its id (damaged on the way?)");
            }
            Files.createDirectories(folder);
            Path temp = Files.createTempFile(folder, texture, ".part");
            try {
                Files.write(temp, data);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return data;
        }
    }

    /** The texture as the game draws it: 64x64 (a 64x32 skin upgraded), base layers opaque. A copy. */
    public SkinImage.Pixels image(String texture) throws IOException {
        SkinImage.Pixels cached = images.get(texture);
        if (cached == null) {
            byte[] data = raw(texture);
            try {
                cached = SkinImage.asTheGameDraws(SkinImage.read(data));
            } catch (IllegalArgumentException e) {
                throw new IOException("texture " + texture + ": " + e.getMessage(), e);
            }
            images.put(texture, cached);
        }
        return cached.copy();
    }

    /** {@link #image} as PNG bytes. */
    public byte[] png(String texture) throws IOException {
        return SkinImage.png(image(texture));
    }
}
