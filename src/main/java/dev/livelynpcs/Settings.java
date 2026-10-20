package dev.livelynpcs;

import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Typed, immutable view of config.yml. A reload builds a new instance. Every key has a default and
 * bounds; a bad value is recorded in {@link #problems} (path: reason) and the default is used, so a
 * typo never stops the plugin. Kept free of server calls so it can be tested.
 */
public final class Settings {

    /** How players get the NPC models. */
    public enum PackMode { OWN, MERGED }

    /** Where an own pack is downloaded from. */
    public enum PackHost { BUILTIN, URL }

    // npcs:
    public final double lookRadius, animateRadius, greetRadius, beckonFrom, beckonTo;
    public final int beckonEveryTicks, idleTicks, breathTicks, keyTicks;
    public final float scale;
    public final boolean sparkles;

    // pack:
    public final PackMode packMode;
    public final PackHost packHost;
    /** own/url: the pack's link, or null when not set (or not a web address). */
    public final URI packUrl;
    /** own/url: the pack's SHA-1 (40 lowercase hex), or "" when not set. */
    public final String packSha1;
    public final String packPrompt;
    public final boolean packRequired;
    public final String mergedUrlContains;

    // web:
    public final boolean webEnabled;
    public final int webPort;
    public final String webBind, webPublicAddress;
    public final int editorMinutes;

    // skins:
    public final String mineskinKey;

    public final List<String> problems;

    public Settings(ConfigurationSection c) {
        List<String> out = new ArrayList<>();
        Reader r = new Reader(c, out);

        lookRadius = r.num("npcs.look-radius", 8, 0, 64);
        animateRadius = r.num("npcs.animate-radius", 24, 0, 128);
        greetRadius = r.num("npcs.greet-radius", 5, 0, 32);
        beckonFrom = r.num("npcs.beckon-from", 6, 0, 64);
        double to = r.num("npcs.beckon-to", 16, 0, 64);
        if (to > 0 && to <= beckonFrom) {
            out.add("npcs.beckon-to: must be more than beckon-from (" + beckonFrom + "), so beckoning is off");
            to = 0;
        }
        beckonTo = to;
        beckonEveryTicks = (int) Math.round(r.num("npcs.beckon-every", 8, 1, 600) * 20);
        idleTicks = (int) Math.round(r.num("npcs.idle-seconds", 12, 0, 3600) * 20);
        breathTicks = (int) Math.round(r.num("npcs.breath-seconds", 3, 1, 10) * 20);
        scale = (float) r.num("npcs.scale", 0.9375, 0.25, 4);
        keyTicks = (int) r.num("npcs.key-ticks", 2, 1, 10);
        sparkles = r.bool("npcs.sparkles", true);

        packMode = r.choice("pack.mode", PackMode.OWN, PackMode.class);
        packHost = r.choice("pack.host", PackHost.BUILTIN, PackHost.class);
        String url = r.str("pack.url", "").trim();
        URI uri = null;
        if (!url.isEmpty()) {
            try {
                uri = new URI(url);
                if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    uri = null;
                }
            } catch (Exception e) {
                uri = null;
            }
            if (uri == null) out.add("pack.url: '" + url + "' is not a web address (http:// or https://)");
        }
        packUrl = uri;
        String sha1 = r.str("pack.sha1", "").trim().toLowerCase(Locale.ROOT);
        if (!sha1.isEmpty() && !sha1.matches("[0-9a-f]{40}")) {
            out.add("pack.sha1: '" + sha1 + "' is not a SHA-1 (40 characters, 0-9 and a-f)");
            sha1 = "";
        }
        packSha1 = sha1;
        if (packMode == PackMode.OWN && packHost == PackHost.URL && (packUrl == null || packSha1.isEmpty())) {
            out.add("pack: host is url, but url and sha1 are not both set, so no pack is sent");
        }
        packPrompt = r.str("pack.prompt", "<gray>Lively NPCs: NPCs that wave, talk and move.");
        packRequired = r.bool("pack.required", false);
        mergedUrlContains = r.str("pack.merged-url-contains", "").trim();

        webEnabled = r.bool("web.enabled", true);
        webPort = (int) r.num("web.port", 8193, 1, 65535);
        webBind = r.str("web.bind", "0.0.0.0").trim();
        webPublicAddress = r.str("web.public-address", "").trim();
        editorMinutes = (int) r.num("web.editor-minutes", 120, 1, 7 * 24 * 60);

        mineskinKey = r.str("skins.mineskin-key", "").trim();

        problems = List.copyOf(out);
    }

    /** Reads keys with defaults and bounds, recording problems as "path: reason". */
    private record Reader(ConfigurationSection c, List<String> problems) {

        double num(String path, double def, double min, double max) {
            if (!c.contains(path)) return def;
            Object v = c.get(path);
            if (!(v instanceof Number n)) {
                problems.add(path + ": '" + v + "' is not a number, using " + def);
                return def;
            }
            double d = n.doubleValue();
            if (Double.isNaN(d) || d < min || d > max) {
                problems.add(path + ": " + d + " is outside " + min + ".." + max + ", using " + def);
                return def;
            }
            return d;
        }

        boolean bool(String path, boolean def) {
            if (!c.contains(path)) return def;
            Object v = c.get(path);
            if (v instanceof Boolean b) return b;
            problems.add(path + ": '" + v + "' is not true or false, using " + def);
            return def;
        }

        String str(String path, String def) {
            if (!c.contains(path)) return def;
            Object v = c.get(path);
            if (v == null) return def;
            if (v instanceof String s) return s;
            if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
            problems.add(path + ": is not text, using '" + def + "'");
            return def;
        }

        <E extends Enum<E>> E choice(String path, E def, Class<E> type) {
            String s = str(path, def.name()).trim().toUpperCase(Locale.ROOT);
            try {
                return Enum.valueOf(type, s);
            } catch (IllegalArgumentException e) {
                StringBuilder allowed = new StringBuilder();
                for (E e2 : type.getEnumConstants()) allowed.append(allowed.isEmpty() ? "" : ", ").append(e2.name().toLowerCase(Locale.ROOT));
                problems.add(path + ": '" + s.toLowerCase(Locale.ROOT) + "' is not one of " + allowed + ", using "
                        + def.name().toLowerCase(Locale.ROOT));
                return def;
            }
        }
    }
}
