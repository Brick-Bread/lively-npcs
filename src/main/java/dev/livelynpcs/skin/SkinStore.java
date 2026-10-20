package dev.livelynpcs.skin;

import dev.livelynpcs.LivelyNpcs;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * The skins NPCs wear: plugins/LivelyNPCs/skins.yml (see {@link SkinFile}), their textures in skins/
 * ({@link TextureCache}), new ones from Mojang ({@link MojangApi}) or MineSkin ({@link MineSkinApi}).
 * Lookups are safe from any thread (the map is replaced whole on every change); web requests run on
 * Bukkit's async threads and their futures complete on the main thread.
 */
public final class SkinStore implements SkinService {

    private static final String FILE = "skins.yml";

    private final LivelyNpcs plugin;
    private final String userAgent;
    private final Http http = Http.jdk();
    private final TextureCache textures;
    private final MineSkinApi mineSkin;
    private volatile SortedMap<String, Skin> skins = Collections.emptySortedMap();

    public SkinStore(LivelyNpcs plugin) {
        this.plugin = plugin;
        this.userAgent = "LivelyNPCs/" + plugin.getPluginMeta().getVersion();
        this.textures = new TextureCache(plugin.getDataFolder().toPath().resolve("skins"), TextureCache.web(userAgent));
        this.mineSkin = new MineSkinApi(http, userAgent, MineSkinApi.Clock.SYSTEM);
    }

    @Override
    public Skin get(String key) {
        return key == null ? null : skins.get(key);
    }

    @Override
    public Collection<Skin> all() {
        return Collections.unmodifiableCollection(skins.values());
    }

    @Override
    public CompletableFuture<Skin> fromPlayer(String name) {
        String trimmed = name == null ? "" : name.trim();
        MojangApi mojang = new MojangApi(http, userAgent);
        return signed(() -> {
            MojangApi.Signed s = mojang.lookup(trimmed);
            return new String[]{s.value(), s.signature(), "player:" + s.name()};
        });
    }

    @Override
    public CompletableFuture<Skin> fromUrl(String url, boolean slim) {
        String link = url == null ? "" : url.trim();
        String key = plugin.settings().mineskinKey;
        if (key.isEmpty()) return CompletableFuture.failedFuture(new SkinException(MineSkinApi.NO_KEY));
        try {
            URI uri = new URI(link);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) throw new IllegalArgumentException();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new SkinException("'" + link + "' is not a web link to an image (http:// or https://)."));
        }
        return signed(() -> {
            MineSkinApi.Signed s = mineSkin.fromUrl(key, link, slim);
            return new String[]{s.value(), s.signature(), "url:" + link};
        });
    }

    @Override
    public CompletableFuture<Skin> fromUpload(byte[] png, boolean slim) {
        String key = plugin.settings().mineskinKey;
        if (key.isEmpty()) return CompletableFuture.failedFuture(new SkinException(MineSkinApi.NO_KEY));
        try {
            SkinImage.Pixels image = SkinImage.read(png == null ? new byte[0] : png);
            if (image.width() != 64 || (image.height() != 64 && image.height() != 32)) {
                return CompletableFuture.failedFuture(new SkinException("That image is " + image.width() + "x" + image.height()
                        + "; a skin is a 64x64 (or old 64x32) PNG."));
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new SkinException("That file is not a PNG image; a skin is a 64x64 PNG."));
        }
        byte[] copy = png.clone();
        return signed(() -> {
            MineSkinApi.Signed s = mineSkin.fromUpload(key, copy, slim);
            return new String[]{s.value(), s.signature(), "upload"};
        });
    }

    /** Runs a web lookup off the main thread ({value, signature, source}), then stores the skin on the main thread. */
    private CompletableFuture<Skin> signed(Callable<String[]> lookup) {
        CompletableFuture<Skin> result = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String[] got;
                try {
                    got = lookup.call();
                } catch (SkinException e) {
                    onMain(() -> result.completeExceptionally(e), result);
                    return;
                } catch (Exception e) {
                    plugin.getLogger().warning("Skin lookup failed: " + e);
                    SkinException failure = new SkinException("The skin could not be had (" + e.getMessage() + ").", e);
                    onMain(() -> result.completeExceptionally(failure), result);
                    return;
                }
                onMain(() -> {
                    try {
                        result.complete(fromSigned(got[0], got[1], got[2]));
                    } catch (IllegalArgumentException e) {
                        result.completeExceptionally(new SkinException("The skin came back unusable: " + e.getMessage() + ".", e));
                    }
                }, result);
            });
        } catch (IllegalPluginAccessException e) {
            result.completeExceptionally(new SkinException("Lively NPCs is turning off; try again in a moment.", e));
        }
        return result;
    }

    private void onMain(Runnable task, CompletableFuture<Skin> result) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) {
            result.completeExceptionally(new SkinException("Lively NPCs was turned off meanwhile; try again.", e));
        }
    }

    @Override
    public Skin fromSigned(String value, String signature, String source) {
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("the skin has no signature (it must be signed by Mojang)");
        try {
            Base64.getDecoder().decode(signature.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the skin's signature is not base64");
        }
        TextureValue parsed = TextureValue.parse(value);
        Skin skin = new Skin(parsed.key(), parsed.texture(), value.replaceAll("\\s", ""), signature.replaceAll("\\s", ""),
                parsed.slim(), source == null ? "" : source);
        store(skin);
        return skin;
    }

    /** Adds or replaces a skin and writes skins.yml. */
    private synchronized void store(Skin skin) {
        Skin before = skins.get(skin.key());
        if (before != null && !before.texture().equals(skin.texture())) {
            throw new IllegalArgumentException("another skin already has the key " + skin.key());
        }
        if (skin.equals(before)) return;
        TreeMap<String, Skin> next = new TreeMap<>(skins);
        next.put(skin.key(), skin);
        skins = Collections.unmodifiableSortedMap(next);
        save();
    }

    private synchronized void save() {
        Path file = plugin.getDataFolder().toPath().resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE + ".part");
            Files.writeString(temp, SkinFile.write(skins.values()), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write " + FILE + " (" + e.getMessage() + "): new skins are lost on restart");
        }
    }

    @Override
    public byte[] png(Skin skin) throws IOException {
        return textures.png(skin.texture());
    }

    @Override
    public synchronized void reload() {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) {
            skins = Collections.emptySortedMap();
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            plugin.getLogger().warning(FILE + " could not be read (" + e.getMessage() + "); the skins stay as they were");
            return;
        }
        List<String> problems = new ArrayList<>();
        TreeMap<String, Skin> read = new TreeMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("skins");
        if (section == null && yaml.contains("skins") && yaml.get("skins") != null) {
            problems.add("skins: is not a list of skins by key, so no skins are read");
        }
        if (section != null) {
            for (String name : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(name);
                Skin skin = SkinFile.entry(name, entry == null ? section.get(name) : entry.getValues(false), problems);
                if (skin == null) continue;
                if (read.put(skin.key(), skin) != null) {
                    problems.add("skins." + name + ": a second entry for skin " + skin.key() + "; the last one is kept");
                }
            }
        }
        for (String problem : problems) plugin.getLogger().warning(FILE + " " + problem);
        skins = Collections.unmodifiableSortedMap(read);
    }
}
