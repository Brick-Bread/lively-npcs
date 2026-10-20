package dev.livelynpcs.anim;

import dev.livelynpcs.LivelyNpcs;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The animations: the presets that ship in the jar (animations/&lt;id&gt;.yml, ids in {@link #PRESETS})
 * and the server's own in plugins/LivelyNPCs/animations/&lt;id&gt;.yml. A server file with a preset's id
 * replaces that preset here; deleting it brings the preset back. A file that cannot be read is logged
 * with its name and left out, so one typo never stops the rest. Main thread only.
 */
public final class AnimationStore implements AnimationService {

    /** The presets the jar ships, by id. */
    public static final List<String> PRESETS = List.of("greet", "wave", "talk", "beckon", "stretch", "look_around", "nod",
            "head_shake", "shrug", "scratch_head", "clap", "foot_tap", "look_up", "hop",
            "bow", "cheer", "point", "check_item", "dance", "wave_both", "salute", "yawn");
    /** What an NPC plays now and then when npcs.yml says idle: [] (it never plays one twice in a row). */
    public static final List<String> DEFAULT_IDLE = List.of("stretch", "look_around", "wave", "nod", "head_shake", "shrug",
            "scratch_head", "clap", "foot_tap", "look_up");
    /** Means "no animation" in commands and npcs.yml, so no animation may be called it. */
    public static final String NONE = "none";

    private final Path folder;
    private final Function<String, InputStream> resources;
    private final Logger log;
    private final Map<String, Animation> presets = new TreeMap<>(), own = new TreeMap<>(), all = new TreeMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public AnimationStore(LivelyNpcs plugin) {
        this(plugin.getDataFolder().toPath().resolve("animations"), plugin::getResource, plugin.getLogger());
    }

    /** For tests: the server's folder, where the jar's files come from, and where problems go. */
    AnimationStore(Path folder, Function<String, InputStream> resources, Logger log) {
        this.folder = folder;
        this.resources = resources;
        this.log = log;
    }

    @Override
    public Collection<Animation> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(all.values()));
    }

    @Override
    public Animation get(String id) {
        return id == null ? null : all.get(id);
    }

    @Override
    public Animation preset(String id) {
        return id == null ? null : presets.get(id);
    }

    /** Whether the server has its own file for this id (its own animation, or an edited preset). */
    public boolean saved(String id) {
        return own.containsKey(id);
    }

    @Override
    public void save(Animation animation) throws IOException {
        if (animation.id().equals(NONE)) {
            throw new IllegalArgumentException("'none' means no animation, so an animation cannot be called that: pick another name");
        }
        Animation stored = new Animation(animation.id(), animation.name(), animation.length(), animation.loop(),
                animation.glance(), animation.keyframes(), false);
        Files.createDirectories(folder);
        Path file = file(stored.id());
        Path temp = folder.resolve(stored.id() + ".yml.tmp");
        Files.writeString(temp, write(stored), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        own.put(stored.id(), stored);
        merge();
        changed();
    }

    @Override
    public boolean delete(String id) throws IOException {
        if (id == null || !Animation.ID.matcher(id).matches()) return false;
        boolean had = own.remove(id) != null;
        had |= Files.deleteIfExists(file(id));
        if (!had) return false;
        merge();
        changed();
        return true;
    }

    @Override
    public void reload() {
        presets.clear();
        for (String id : PRESETS) {
            try (InputStream in = resources.apply("animations/" + id + ".yml")) {
                if (in == null) {
                    log.warning("The preset animation '" + id + "' is missing from the jar");
                    continue;
                }
                Animation a = read(id, new String(in.readAllBytes(), StandardCharsets.UTF_8), true, "the jar's animations/" + id + ".yml");
                if (a != null) presets.put(id, a);
            } catch (IOException e) {
                log.warning("The preset animation '" + id + "' could not be read: " + e.getMessage());
            }
        }
        own.clear();
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.yml")) {
                for (Path f : files) {
                    String name = f.getFileName().toString();
                    String id = name.substring(0, name.length() - 4);
                    String where = "animations/" + name;
                    if (!Animation.ID.matcher(id).matches() || id.equals(NONE)) {
                        log.warning(where + ": the file name must be an animation id (a-z, 0-9, _ and -, not 'none'), so it is left out");
                        continue;
                    }
                    try {
                        Animation a = read(id, Files.readString(f, StandardCharsets.UTF_8), false, where);
                        if (a != null) own.put(id, a);
                    } catch (IOException e) {
                        log.warning(where + ": could not be read (" + e.getMessage() + "), so it is left out");
                    }
                }
            } catch (IOException e) {
                log.warning("The animations folder could not be read: " + e.getMessage());
            }
        }
        merge();
        changed();
    }

    @Override
    public void addListener(Runnable onChange) {
        listeners.add(onChange);
    }

    private void merge() {
        all.clear();
        all.putAll(presets);
        all.putAll(own);
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warning("An animation listener failed: " + e);
            }
        }
    }

    private Path file(String id) {
        return folder.resolve(id + ".yml");
    }

    /** Parses one file's text; problems are logged with where it came from. Null (logged) if it cannot be used. */
    private Animation read(String id, String text, boolean builtin, String where) {
        Object root;
        try {
            root = yaml().load(text);
        } catch (RuntimeException e) {
            log.warning(where + ": is not valid YAML (" + firstLine(e.getMessage()) + "), so it is left out");
            return null;
        }
        if (!(root instanceof Map<?, ?> map)) {
            log.warning(where + ": is empty or not an animation, so it is left out");
            return null;
        }
        List<String> problems = new ArrayList<>();
        try {
            Animation a = parse(id, map, builtin, problems);
            for (String problem : problems) log.warning(where + ": " + problem);
            if (!a.loop() && !a.endsAtRest()) {
                log.warning(where + ": does not end at rest (every value 0), so the NPC jumps back to its own pose at the end");
            }
            return a;
        } catch (IllegalArgumentException e) {
            log.warning(where + ": " + e.getMessage() + ", so it is left out");
            return null;
        }
    }

    /** Animation.fromMap, plus a check of the keys it does not look at (so a typo is reported). */
    static Animation parse(String id, Map<?, ?> map, boolean builtin, List<String> problems) {
        for (Object key : map.keySet()) {
            if (!List.of("name", "length", "loop", "glance", "keyframes").contains(String.valueOf(key))) {
                problems.add("unknown key '" + key + "' (name, length, loop, glance and keyframes are read)");
            }
        }
        return Animation.fromMap(id, map, builtin, problems);
    }

    /** The file text for an animation: a short header, then its map with whole numbers written as such. */
    static String write(Animation animation) {
        Map<String, Object> map = animation.toMap();
        List<Map<String, Object>> keys = new ArrayList<>();
        for (Object k : (List<?>) map.get("keyframes")) {
            Map<String, Object> km = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) k).entrySet()) km.put(String.valueOf(e.getKey()), tidy(e.getValue()));
            keys.add(km);
        }
        map.put("keyframes", keys);
        return "# Saved by Lively NPCs (the web editor, or by hand). Ticks: 20 a second; a value left out is 0.\n"
                + yaml().dump(map);
    }

    private static Object tidy(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e9) return (int) Math.rint(d);
        return v;
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setWidth(120);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        LoaderOptions loader = new LoaderOptions();
        return new Yaml(new SafeConstructor(loader), new Representer(options), options, loader);
    }

    private static String firstLine(String s) {
        if (s == null) return "unreadable";
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }
}
