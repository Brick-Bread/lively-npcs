package dev.livelynpcs.pack;

import dev.livelynpcs.skin.Json;
import dev.livelynpcs.skin.SkinImage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds the NPC resource pack (see DESIGN.md "Pack"): pack.mcmeta, the items and blocks atlas
 * entries, and for every skin key its texture (assets/livelynpcs/textures/livelynpcs/&lt;key&gt;.png), six
 * item definitions (assets/livelynpcs/items/&lt;key&gt;/&lt;part&gt;.json) and six models
 * (assets/livelynpcs/models/npc/&lt;key&gt;/&lt;part&gt;.json), each skin's models checked with
 * {@link ModelBuilder#check}. The zip is deterministic: entries sorted by name, one fixed timestamp,
 * the same compression, so the same skins always give the same bytes and SHA-1. JDK only.
 */
public final class PackBuilder {

    /** pack.mcmeta's numbers: 46 (1.21.4) is the oldest format with item model definitions; 97.1 is 26.3's. */
    public static final int MIN_FORMAT = 46;
    public static final int MAX_FORMAT = 97, MAX_FORMAT_MINOR = 1;
    public static final String DESCRIPTION = "Lively NPCs";
    public static final String MCMETA = "pack.mcmeta";
    /** The whole pack, in export/ (own/url mode uploads it; --zip in ExportCli). */
    public static final String PACK_ZIP = "lively-npcs-pack.zip";
    /** The assets alone, in export/ (merged mode). */
    public static final String ASSETS_ZIP = "lively-npcs-assets.zip";
    public static final String ITEMS_ATLAS = "assets/minecraft/atlases/items.json";
    public static final String BLOCKS_ATLAS = "assets/minecraft/atlases/blocks.json";
    static final String NAMESPACE_DIR = "assets/" + ModelBuilder.NAMESPACE + "/";
    static final String TEXTURE_DIR = NAMESPACE_DIR + "textures/" + ModelBuilder.NAMESPACE + "/";
    /** Every entry's time in the zip (stored as DOS local time, so the time zone never matters). */
    static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
    private static final Pattern TEXTURE_ENTRY = Pattern.compile(Pattern.quote(TEXTURE_DIR) + "([0-9a-f]{16})\\.png");

    /** One skin to put in: its key, whether its arms are slim, its picture (64x64, or 64x32 to upgrade). */
    public record SkinInput(String key, boolean slim, SkinImage.Pixels image) {
    }

    /**
     * A built pack: the skin keys in it, its files (path to bytes, sorted), the zip and the zip's SHA-1
     * (40 lowercase hex).
     */
    public record Pack(SortedSet<String> keys, SortedMap<String, byte[]> files, byte[] zip, String sha1) {

        /** The assets alone (no pack.mcmeta), zipped the same way: what merged mode hands the server's pack. */
        public byte[] assetsZip() {
            SortedMap<String, byte[]> assets = new TreeMap<>(files);
            assets.remove(MCMETA);
            return PackBuilder.zip(assets);
        }
    }

    private PackBuilder() {
    }

    /** Builds the pack for these skins (any order; a key twice keeps the last). */
    public static Pack build(Collection<SkinInput> skins) {
        Map<String, SkinInput> byKey = new TreeMap<>();
        for (SkinInput s : skins) {
            if (!dev.livelynpcs.skin.Skin.KEY.matcher(s.key()).matches()) throw new IllegalArgumentException("'" + s.key() + "' is not a skin key");
            byKey.put(s.key(), s);
        }
        SortedMap<String, byte[]> files = new TreeMap<>();
        files.put(MCMETA, json(mcmeta()));
        files.put(ITEMS_ATLAS, json(atlas()));
        files.put(BLOCKS_ATLAS, json(atlas()));
        for (SkinInput s : byKey.values()) {
            SkinImage.Pixels image = SkinImage.asTheGameDraws(s.image());
            Map<String, Map<String, Object>> models = new HashMap<>();
            Map<String, Map<String, Object>> items = new HashMap<>();
            for (String part : ModelBuilder.parts()) {
                Map<String, Object> model = ModelBuilder.partModel(s.key(), part, s.slim());
                Map<String, Object> item = ModelBuilder.itemDefinition(s.key(), part);
                models.put(part, model);
                items.put(part, item);
                files.put(NAMESPACE_DIR + "models/npc/" + s.key() + "/" + part + ".json", json(model));
                files.put(NAMESPACE_DIR + "items/" + s.key() + "/" + part + ".json", json(item));
            }
            ModelBuilder.check(s.key(), s.slim(), image, models, items);
            files.put(TEXTURE_DIR + s.key() + ".png", SkinImage.png(image));
        }
        byte[] zip = zip(files);
        return new Pack(Collections.unmodifiableSortedSet(new TreeSet<>(byKey.keySet())),
                Collections.unmodifiableSortedMap(files), zip, SkinImage.sha1(zip));
    }

    /** pack.mcmeta as DESIGN.md has it. */
    static Map<String, Object> mcmeta() {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("pack_format", MIN_FORMAT);
        pack.put("supported_formats", List.of(MIN_FORMAT, MAX_FORMAT));
        pack.put("min_format", MIN_FORMAT);
        pack.put("max_format", List.of(MAX_FORMAT, MAX_FORMAT_MINOR));
        pack.put("description", DESCRIPTION);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pack", pack);
        return root;
    }

    /** Our atlas source: every texture under textures/livelynpcs/ (26.2+ read the items atlas, older the blocks one). */
    static Map<String, Object> atlasSource() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "directory");
        source.put("source", ModelBuilder.NAMESPACE);
        source.put("prefix", ModelBuilder.NAMESPACE + "/");
        return source;
    }

    static Map<String, Object> atlas() {
        Map<String, Object> atlas = new LinkedHashMap<>();
        atlas.put("sources", List.of(atlasSource()));
        return atlas;
    }

    private static byte[] json(Object value) {
        return Json.pretty(value).getBytes(StandardCharsets.UTF_8);
    }

    /** These files as a zip: sorted, fixed time, best compression, no directory entries. */
    static byte[] zip(SortedMap<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            zip.setMethod(ZipOutputStream.DEFLATED);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTimeLocal(FIXED_TIME);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e); // a byte array never fails
        }
        return bytes.toByteArray();
    }

    /**
     * Reads back a pack zip this class built (export/lively-npcs-pack.zip after a restart): its files, its
     * skin keys (from the textures in it) and its SHA-1. IOException when it is not such a pack.
     */
    public static Pack read(byte[] zip) throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        SortedSet<String> keys = new TreeSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                files.put(entry.getName(), in.readAllBytes());
                Matcher m = TEXTURE_ENTRY.matcher(entry.getName());
                if (m.matches()) keys.add(m.group(1));
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("not a zip (" + e.getMessage() + ")", e);
        }
        if (!files.containsKey(MCMETA) || !files.containsKey(ITEMS_ATLAS)) throw new IOException("not a Lively NPCs pack");
        for (String key : keys) {
            for (String part : ModelBuilder.parts()) {
                if (!files.containsKey(NAMESPACE_DIR + "models/npc/" + key + "/" + part + ".json")
                        || !files.containsKey(NAMESPACE_DIR + "items/" + key + "/" + part + ".json")) {
                    throw new IOException("skin " + key + " is missing its " + part + " model");
                }
            }
        }
        return new Pack(Collections.unmodifiableSortedSet(keys), Collections.unmodifiableSortedMap(files), zip, SkinImage.sha1(zip));
    }

    /**
     * Writes the pack's assets (no pack.mcmeta) under dir, as merged mode's export/ and ExportCli do:
     * dir/assets/livelynpcs/ is replaced whole (so skins no longer in the pack go), and the two atlas
     * files get our source: written new, or added to an atlas file already there (another pack's).
     */
    public static void writeAssets(Path dir, Pack pack) throws IOException {
        Path ours = dir.resolve(NAMESPACE_DIR);
        deleteTree(ours);
        for (Map.Entry<String, byte[]> e : pack.files().entrySet()) {
            if (!e.getKey().startsWith(NAMESPACE_DIR)) continue;
            Path file = dir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        for (String atlas : List.of(ITEMS_ATLAS, BLOCKS_ATLAS)) {
            Path file = dir.resolve(atlas);
            Files.createDirectories(file.getParent());
            byte[] content = pack.files().get(atlas);
            if (Files.isRegularFile(file)) content = mergeAtlas(file, Files.readString(file, StandardCharsets.UTF_8));
            if (content != null) Files.write(file, content);
        }
    }

    /** An atlas file's text with our source added (null when it already has it). */
    @SuppressWarnings("unchecked")
    static byte[] mergeAtlas(Path file, String text) throws IOException {
        Map<String, Object> atlas;
        try {
            atlas = Json.object(text);
        } catch (IllegalArgumentException e) {
            throw new IOException(file + " is already there and is not JSON (" + e.getMessage() + "); fix or delete it");
        }
        Object sources = atlas.get("sources");
        if (sources != null && !(sources instanceof List)) throw new IOException(file + ": \"sources\" is not a list; fix or delete it");
        List<Object> list = sources == null ? new ArrayList<>() : new ArrayList<>((List<Object>) sources);
        for (Object s : list) {
            if (!(s instanceof Map<?, ?> m)) continue;
            String type = String.valueOf(m.get("type"));
            if ((type.equals("directory") || type.equals("minecraft:directory")) && ModelBuilder.NAMESPACE.equals(m.get("source"))
                    && (ModelBuilder.NAMESPACE + "/").equals(m.get("prefix"))) {
                return null;
            }
        }
        list.add(atlasSource());
        Map<String, Object> merged = new LinkedHashMap<>(atlas);
        merged.put("sources", list);
        return json(merged);
    }

    /** Writes bytes to a file through a temporary file, so a reader never sees half of it. */
    public static void writeAtomically(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(temp, data);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = new ArrayList<>(walk.toList());
        }
        paths.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path p : paths) Files.deleteIfExists(p);
    }
}
