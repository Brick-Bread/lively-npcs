package dev.livelynpcs.pack;

import dev.livelynpcs.skin.Skin;
import dev.livelynpcs.skin.SkinFile;
import dev.livelynpcs.skin.TextureCache;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The NPC models without a server, for admins and for another pack's build (RaiseAMob merges them into
 * its own pack):
 *
 * <pre>
 * java -cp LivelyNPCs-&lt;version&gt;.jar dev.livelynpcs.pack.ExportCli --skins plugins/LivelyNPCs/skins.yml \
 *      --out export [--cache plugins/LivelyNPCs/skins] [--zip]
 * </pre>
 *
 * Reads every skin in skins.yml, takes each texture from the cache folder (default: skins/ beside
 * skins.yml, as the plugin keeps them) or downloads it from textures.minecraft.net into it, checked
 * against its texture id, and writes what merged mode writes into export/: the assets folder (item
 * definitions, models, textures, both atlas files) and lively-npcs-assets.zip; with --zip also the
 * whole pack, lively-npcs-pack.zip (pack.mcmeta included). Exits 0 when done, 1 on any failure and 2
 * for wrong arguments, with a message saying why. Uses the JDK only: the jar holds no server classes.
 */
public final class ExportCli {

    static final String USAGE = "Usage: java -cp LivelyNPCs.jar dev.livelynpcs.pack.ExportCli --skins <skins.yml> --out <folder> "
            + "[--cache <folder for skin PNGs>] [--zip]";

    private ExportCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Does the export; returns the exit code (0 done, 1 failed, 2 wrong arguments). */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Path skinsFile = null, outDir = null, cacheDir = null;
        boolean zip = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--skins", "--out", "--cache" -> {
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        err.println(a + " needs a path after it.");
                        err.println(USAGE);
                        return 2;
                    }
                    Path p = Path.of(args[++i]);
                    if (a.equals("--skins")) skinsFile = p;
                    else if (a.equals("--out")) outDir = p;
                    else cacheDir = p;
                }
                case "--zip" -> zip = true;
                case "--help", "-h" -> {
                    out.println(USAGE);
                    return 0;
                }
                default -> {
                    err.println("Unknown argument '" + a + "'.");
                    err.println(USAGE);
                    return 2;
                }
            }
        }
        if (skinsFile == null || outDir == null) {
            err.println("Both --skins and --out are needed.");
            err.println(USAGE);
            return 2;
        }
        try {
            export(skinsFile, outDir, cacheDir, zip, out);
            return 0;
        } catch (Exception e) {
            err.println("Lively NPCs export failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        }
    }

    private static void export(Path skinsFile, Path outDir, Path cacheDir, boolean zip, PrintStream out) throws IOException {
        if (!Files.isRegularFile(skinsFile)) throw new IOException("there is no file " + skinsFile.toAbsolutePath());
        SkinFile.Read read;
        try {
            read = SkinFile.read(Files.readString(skinsFile, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException(skinsFile + " " + e.getMessage(), e);
        }
        for (String problem : read.problems()) out.println("Warning: " + skinsFile.getFileName() + " " + problem);
        Path cache = cacheDir != null ? cacheDir : skinsFile.toAbsolutePath().getParent().resolve("skins");
        TextureCache textures = new TextureCache(cache, TextureCache.web("LivelyNPCs-export/" + version()));
        List<PackBuilder.SkinInput> inputs = new ArrayList<>();
        for (Skin skin : read.skins()) {
            try {
                inputs.add(new PackBuilder.SkinInput(skin.key(), skin.slim(), textures.image(skin.texture())));
            } catch (IOException e) {
                throw new IOException("skin " + skin.key() + ": " + e.getMessage(), e);
            }
        }
        PackBuilder.Pack pack = PackBuilder.build(inputs);
        Files.createDirectories(outDir);
        PackBuilder.writeAssets(outDir, pack);
        PackBuilder.writeAtomically(outDir.resolve(PackBuilder.ASSETS_ZIP), pack.assetsZip());
        out.println("Wrote " + pack.keys().size() + " skin(s) to " + outDir.resolve("assets").toAbsolutePath()
                + " and " + PackBuilder.ASSETS_ZIP);
        if (zip) {
            PackBuilder.writeAtomically(outDir.resolve(PackBuilder.PACK_ZIP), pack.zip());
            out.println("Wrote " + outDir.resolve(PackBuilder.PACK_ZIP).toAbsolutePath() + " (" + pack.zip().length
                    + " bytes), SHA-1 " + pack.sha1());
        }
    }

    /** The plugin's version from plugin.yml in the jar, for the User-Agent. */
    static String version() {
        try (InputStream in = ExportCli.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            if (in == null) return "dev";
            Matcher m = Pattern.compile("(?m)^version:\\s*['\"]?([^'\"\\s]+)").matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return m.find() && !m.group(1).contains("$") ? m.group(1).toLowerCase(Locale.ROOT) : "dev";
        } catch (IOException e) {
            return "dev";
        }
    }
}
