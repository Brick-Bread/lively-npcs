package dev.livelynpcs.npc;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * plugins/LivelyNPCs/npcs.yml, in exactly the shape DESIGN.md "npcs.yml" shows: {@code npcs:} then one
 * map per NPC id, keys in a fixed order, lists in [flow, style]. Reading is forgiving: a value of the
 * wrong kind is reported ("npcs.&lt;id&gt;.&lt;key&gt;: why") and its default used; an NPC without a world
 * is reported and left out. Kept free of server classes so it can be tested.
 */
public final class NpcFile {

    /** What a read found: the NPCs (sorted by id) and the problems, for the log. */
    public record Loaded(List<NpcDef> npcs, List<String> problems) {
    }

    static final List<String> KEYS = List.of("entity", "variant", "world", "x", "y", "z", "yaw", "skin", "plate", "icon",
            "held", "click", "idle", "greet", "clicked", "beckon", "look");
    private static final String HEADER = """
            # Lively NPCs: every NPC, by id. Change them with /npc or the web editor (/npc editor); if you
            # edit this file by hand, /npc reload reads it again. A value left out takes its default.
            #   entity: player, or a mob (villager, copper_golem, ...)   variant: villagers' type/profession
            #   skin: a key from skins.yml ('' = the default skin)        plate: lines over its head (MiniMessage)
            #   icon / held: an item over the plate / in its left hand ('' = none)
            #   click: player:<command>, console:<command> or message:<text>; {player} is the clicker
            #   idle: [] = the built-in mix, [none] = none; greet / clicked / beckon: an animation, '' = none
            """;

    private NpcFile() {
    }

    /** Reads npcs.yml's text. Never throws: broken YAML is one problem and no NPCs. */
    public static Loaded read(String text) {
        List<String> problems = new ArrayList<>();
        List<NpcDef> out = new ArrayList<>();
        Object root;
        try {
            root = reader().load(text);
        } catch (RuntimeException e) {
            problems.add("is not valid YAML (" + firstLine(e.getMessage()) + "), so no NPCs were read: fix it and /npc reload");
            return new Loaded(out, problems);
        }
        if (root == null) return new Loaded(out, problems);
        if (!(root instanceof Map<?, ?> top) || (top.get("npcs") != null && !(top.get("npcs") instanceof Map<?, ?>))) {
            problems.add("npcs: should be a map of NPCs by id");
            return new Loaded(out, problems);
        }
        if (!(top.get("npcs") instanceof Map<?, ?> all)) return new Loaded(out, problems);
        for (Map.Entry<?, ?> e : all.entrySet()) {
            String id = String.valueOf(e.getKey());
            String at = "npcs." + id;
            if (!NpcDef.ID.matcher(id).matches()) {
                problems.add(at + ": '" + id + "' is not an NPC id (a-z, 0-9, _ and -), left out");
                continue;
            }
            if (!(e.getValue() instanceof Map<?, ?> m)) {
                problems.add(at + ": should be a map of its settings, left out");
                continue;
            }
            NpcDef npc = fromMap(id, m, problems);
            if (npc != null) out.add(npc);
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return new Loaded(out, problems);
    }

    /** One NPC's settings; null (reported) when it cannot be used. */
    static NpcDef fromMap(String id, Map<?, ?> m, List<String> problems) {
        Fields f = new Fields("npcs." + id + ".", m, problems);
        for (Object key : m.keySet()) {
            if (!KEYS.contains(String.valueOf(key))) problems.add(f.at + key + ": not a setting (" + String.join(", ", KEYS) + ")");
        }
        String world = f.text("world", "");
        if (world.isBlank()) {
            problems.add(f.at + "world: missing, so this NPC is left out");
            return null;
        }
        String greet = f.text("greet", NpcDef.DEFAULT_GREET), clicked = f.text("clicked", NpcDef.DEFAULT_CLICKED),
                beckon = f.text("beckon", NpcDef.DEFAULT_BECKON);
        return new NpcDef(id, f.text("entity", "player").toLowerCase(Locale.ROOT), f.text("variant", "").toLowerCase(Locale.ROOT),
                world, f.number("x"), f.number("y"), f.number("z"), (float) f.number("yaw"), f.text("skin", "").toLowerCase(Locale.ROOT),
                f.lines("plate"), f.text("icon", "").toLowerCase(Locale.ROOT), f.text("held", "").toLowerCase(Locale.ROOT),
                f.lines("click"), f.lines("idle"), greet, clicked, beckon, f.bool("look", true));
    }

    /** npcs.yml's text for these NPCs (sorted by id). */
    public static String write(Collection<NpcDef> npcs) {
        Map<String, Object> all = new LinkedHashMap<>();
        npcs.stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(n -> all.put(n.id(), toMap(n)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("npcs", all);
        return HEADER + writer().dump(root);
    }

    /** One NPC as npcs.yml keeps it, keys in DESIGN.md's order. */
    static Map<String, Object> toMap(NpcDef n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity", n.entity());
        m.put("variant", n.variant());
        m.put("world", n.world());
        m.put("x", tidy(n.x(), 4));
        m.put("y", tidy(n.y(), 4));
        m.put("z", tidy(n.z(), 4));
        m.put("yaw", tidy(n.yaw(), 2));
        m.put("skin", new Quoted(n.skin()));       // always quoted: a key of digits must stay text
        m.put("plate", new ArrayList<>(n.plate()));
        m.put("icon", n.icon());
        m.put("held", n.held());
        m.put("click", new ArrayList<>(n.click()));
        m.put("idle", new ArrayList<>(n.idle()));
        m.put("greet", n.greet());
        m.put("clicked", n.clicked());
        m.put("beckon", n.beckon());
        m.put("look", n.look());
        return m;
    }

    /** A number rounded to this many decimals, written as a whole number when it is one. */
    private static Number tidy(double v, int decimals) {
        double scale = Math.pow(10, decimals);
        double r = Math.round(v * scale) / scale;
        if (r == Math.rint(r) && Math.abs(r) < 1e15) return (long) r;
        return r;
    }

    /** A value written in single quotes whatever it holds. */
    private record Quoted(String text) {
    }

    /** SnakeYAML's representer, plus Quoted values in single quotes. */
    private static final class Writer extends Representer {
        Writer(DumperOptions options) {
            super(options);
            representers.put(Quoted.class, data -> representScalar(Tag.STR, ((Quoted) data).text(), DumperOptions.ScalarStyle.SINGLE_QUOTED));
        }
    }

    /**
     * Reads every plain value as the exact text typed (only empty and null stay null), so a skin key of
     * digits or an id like 007 keeps its digits; Fields reads numbers and true/false from that text.
     */
    private static final class TextOnly extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.NULL, NULL, "~nN\0", 10);
            addImplicitResolver(Tag.NULL, EMPTY, null, 10);
        }
    }

    private static Yaml reader() {
        LoaderOptions loader = new LoaderOptions();
        DumperOptions options = new DumperOptions();
        return new Yaml(new SafeConstructor(loader), new Representer(options), options, loader, new TextOnly());
    }

    /** Writes with YAML's usual rules, so any text that could be read as something else is quoted. */
    private static Yaml writer() {
        DumperOptions options = new DumperOptions();
        // AUTO: lists of plain values in [flow, style], maps (which hold lists) as blocks.
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.AUTO);
        options.setIndent(2);
        options.setWidth(4096);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        options.setSplitLines(false);
        LoaderOptions loader = new LoaderOptions();
        return new Yaml(new SafeConstructor(loader), new Writer(options), options, loader);
    }

    private static String firstLine(String s) {
        if (s == null) return "unreadable";
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }

    /** Reads one NPC's values, reporting each bad one with its path. */
    private record Fields(String at, Map<?, ?> m, List<String> problems) {

        String text(String key, String def) {
            if (!m.containsKey(key)) return def;
            Object v = m.get(key);
            if (v == null) return "";
            if (v instanceof String s) return s.trim();
            if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
            problems.add(at + key + ": should be text, using '" + def + "'");
            return def;
        }

        double number(String key) {
            Object v = m.get(key);
            if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
            if (v instanceof String text) {
                try {
                    double d = Double.parseDouble(text.trim().replace("_", ""));
                    if (Double.isFinite(d)) return d;
                } catch (NumberFormatException ignored) {
                    // reported below
                }
            }
            problems.add(at + key + ": " + (v == null ? "missing" : "'" + v + "' is not a number") + ", using 0");
            return 0;
        }

        boolean bool(String key, boolean def) {
            if (!m.containsKey(key)) return def;
            Object v = m.get(key);
            if (v instanceof Boolean b) return b;
            if (v instanceof String text) {
                switch (text.trim().toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "on" -> {
                        return true;
                    }
                    case "false", "no", "off" -> {
                        return false;
                    }
                    default -> { }
                }
            }
            problems.add(at + key + ": should be true or false, using " + def);
            return def;
        }

        List<String> lines(String key) {
            Object v = m.get(key);
            if (v == null) return List.of();
            if (v instanceof String s) return s.isEmpty() ? List.of() : List.of(s);
            if (v instanceof Number || v instanceof Boolean) return List.of(String.valueOf(v));
            if (!(v instanceof List<?> list)) {
                problems.add(at + key + ": should be a list like ['one', 'two'], using []");
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String || o instanceof Number || o instanceof Boolean) out.add(String.valueOf(o));
                else problems.add(at + key + ": '" + o + "' is not text, left out");
            }
            return out;
        }
    }
}
