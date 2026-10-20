package dev.livelynpcs.skin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * skins.yml without the server's YAML library, for ExportCli (the plugin reads it with Bukkit's
 * YamlConfiguration and writes it with {@link #write}). The shape is
 * {@code skins: { <key>: { texture, value, signature, slim, source } }}. The reader takes the YAML that
 * file is written in: block mappings by indentation, plain, 'single' and "double" quoted scalars
 * (quoted ones may run over several lines), comments; anything else (flow collections, block
 * scalars, anchors) is refused with its line number. Each skin's texture and slim come from its signed
 * value, which is what the game reads; a key or texture that disagrees is reported and corrected.
 */
public final class SkinFile {

    /** The skins read, and what was wrong with the file ("line 12: ..."), skipped entries included. */
    public record Read(List<Skin> skins, List<String> problems) {
    }

    private SkinFile() {
    }

    /** skins.yml as Lively NPCs writes it: every key and text double-quoted, so no key reads as a number. */
    public static String write(Collection<Skin> skins) {
        StringBuilder out = new StringBuilder();
        out.append("# Lively NPCs skins. An NPC names its skin by the key (the first 16 characters of the texture).\n");
        out.append("# Added with /npc skin or the web editor: value and signature are the skin Mojang signed, texture\n");
        out.append("# and slim come from them. Removing a skin here takes it off every NPC that wears it.\n");
        if (skins.isEmpty()) return out.append("skins: {}\n").toString();
        out.append("skins:\n");
        List<Skin> sorted = new ArrayList<>(skins);
        sorted.sort(Comparator.comparing(Skin::key));
        for (Skin s : sorted) {
            out.append("  ").append(Json.quoted(s.key())).append(":\n");
            out.append("    texture: ").append(Json.quoted(s.texture())).append('\n');
            out.append("    value: ").append(Json.quoted(s.value())).append('\n');
            out.append("    signature: ").append(Json.quoted(s.signature())).append('\n');
            out.append("    slim: ").append(s.slim()).append('\n');
            out.append("    source: ").append(Json.quoted(s.source())).append('\n');
        }
        return out.toString();
    }

    /** Reads skins.yml text. A file that is not this YAML at all throws IllegalArgumentException ("line N: ..."). */
    public static Read read(String text) {
        Map<String, Object> root = Yaml.parse(text);
        List<String> problems = new ArrayList<>();
        List<Skin> skins = new ArrayList<>();
        Object section = root.get("skins");
        if (section == null) return new Read(skins, problems);
        if (!(section instanceof Map<?, ?> map)) {
            problems.add("skins: is not a list of skins by key, so no skins are read");
            return new Read(skins, problems);
        }
        Map<String, Skin> byKey = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            Skin skin = entry(name, e.getValue(), problems);
            if (skin == null) continue;
            if (byKey.put(skin.key(), skin) != null) problems.add("skins." + name + ": a second entry for skin " + skin.key() + "; the last one is kept");
        }
        skins.addAll(byKey.values());
        return new Read(skins, problems);
    }

    /**
     * One entry as a Skin, its texture and slim taken from the signed value; null (and a problem) when it
     * cannot be used. Shared with SkinStore, which reads the same fields with YamlConfiguration.
     */
    public static Skin entry(String name, Object entry, List<String> problems) {
        if (!(entry instanceof Map<?, ?> fields)) {
            problems.add("skins." + name + ": is not a skin (texture, value, signature, slim, source), skipped");
            return null;
        }
        String value = text(fields.get("value"));
        String signature = text(fields.get("signature"));
        if (value == null || value.isBlank() || signature == null || signature.isBlank()) {
            problems.add("skins." + name + ": needs both value and signature (a skin Mojang signed), skipped");
            return null;
        }
        TextureValue parsed;
        try {
            parsed = TextureValue.parse(value);
        } catch (IllegalArgumentException e) {
            problems.add("skins." + name + ": " + e.getMessage() + ", skipped");
            return null;
        }
        String key = parsed.key();
        if (!name.equals(key)) problems.add("skins." + name + ": its texture's key is " + key + "; NPCs must name it " + key);
        String texture = text(fields.get("texture"));
        if (texture != null && !texture.isBlank() && !texture.trim().toLowerCase(Locale.ROOT).equals(parsed.texture())) {
            problems.add("skins." + name + ": texture does not match the signed value; using " + parsed.texture());
        }
        Object slim = fields.get("slim");
        Boolean stored = slim instanceof Boolean b ? b : slim instanceof String s ? bool(s) : null;
        if (stored != null && stored != parsed.slim()) {
            problems.add("skins." + name + ": slim is " + stored + " but the signed value says " + parsed.slim() + "; using "
                    + parsed.slim());
        }
        String source = text(fields.get("source"));
        // base64 has no spaces: a value folded over lines is joined back whole
        return new Skin(key, parsed.texture(), value.replaceAll("\\s", ""), signature.replaceAll("\\s", ""), parsed.slim(),
                source == null ? "" : source);
    }

    private static String text(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        return String.valueOf(v);
    }

    /** YAML 1.1's words for true and false. */
    static Boolean bool(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "y" -> Boolean.TRUE;
            case "false", "no", "off", "n" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** The YAML subset described above. Scalars come back as String (null for ~, null or nothing). */
    static final class Yaml {

        /** indent -1: a blank or comment line (still kept whole in raw, for quoted values running over lines). */
        private record Line(int number, int indent, String text, String raw) {
        }

        private final List<Line> lines = new ArrayList<>();
        private int at;

        private Yaml(String text) {
            String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (int i = 0; i < raw.length; i++) {
                String line = raw[i];
                if (i == 0 && line.startsWith("﻿")) line = line.substring(1);
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                String rest = line.substring(indent);
                if (rest.isBlank() || rest.startsWith("#")) {
                    lines.add(new Line(i + 1, -1, "", line));
                    continue;
                }
                if (rest.startsWith("\t")) throw new IllegalArgumentException("line " + (i + 1) + ": tabs cannot indent YAML; use spaces");
                if (indent == 0 && (rest.equals("---") || rest.startsWith("--- ") || rest.equals("..."))) {
                    lines.add(new Line(i + 1, -1, "", ""));
                    continue;
                }
                lines.add(new Line(i + 1, indent, rest, line));
            }
        }

        static Map<String, Object> parse(String text) {
            Yaml yaml = new Yaml(text);
            Map<String, Object> root = new LinkedHashMap<>();
            yaml.block(root, -1);
            return root;
        }

        private IllegalArgumentException error(Line line, String what) {
            return new IllegalArgumentException("line " + line.number() + ": " + what);
        }

        private Line next() {
            while (at < lines.size() && lines.get(at).indent() < 0) at++;
            return at < lines.size() ? lines.get(at) : null;
        }

        /** Reads key: value lines indented more than parent into map. */
        private void block(Map<String, Object> map, int parent) {
            int indent = -1;
            while (true) {
                Line line = next();
                if (line == null || line.indent() <= parent) return;
                if (indent < 0) indent = line.indent();
                if (line.indent() != indent) throw error(line, "this line is indented differently from the ones above it");
                at++;
                String text = line.text();
                if (text.startsWith("- ") || text.equals("-")) throw error(line, "a list is not expected here");
                int[] end = new int[1];
                String key = key(line, text, end);
                String rest = text.substring(end[0]).strip();
                if (rest.isEmpty() || rest.startsWith("#")) {
                    Line child = next();
                    if (child != null && child.indent() > indent) {
                        Map<String, Object> inner = new LinkedHashMap<>();
                        block(inner, indent);
                        map.put(key, inner);
                    } else {
                        map.put(key, null);
                    }
                } else {
                    map.put(key, value(line, rest, indent));
                }
            }
        }

        /** The key at the start of text; end[0] is set past its ':'. */
        private String key(Line line, String text, int[] end) {
            if (text.startsWith("\"") || text.startsWith("'")) {
                int[] stop = new int[1];
                String key = quoted(line, text, 0, stop);
                if (stop[0] < 0) throw error(line, "a quoted key must end on its line");
                int i = stop[0];
                while (i < text.length() && text.charAt(i) == ' ') i++;
                if (i >= text.length() || text.charAt(i) != ':') throw error(line, "expected ':' after the key");
                end[0] = i + 1;
                return key;
            }
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == ':' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                    end[0] = i + 1;
                    String key = text.substring(0, i).strip();
                    if (key.isEmpty()) throw error(line, "a key is missing before ':'");
                    return key;
                }
                if (text.charAt(i) == '#' && i > 0 && text.charAt(i - 1) == ' ') break;
            }
            throw error(line, "expected 'key: value'");
        }

        private Object value(Line line, String rest, int indent) {
            char c = rest.charAt(0);
            if (c == '"' || c == '\'') {
                int[] stop = new int[1];
                StringBuilder joined = new StringBuilder(rest);
                // A quoted scalar may run over the following lines.
                while (true) {
                    String text = joined.toString();
                    String value = quoted(line, text, 0, stop);
                    if (stop[0] >= 0) {
                        String after = text.substring(stop[0]).strip();
                        if (!after.isEmpty() && !after.startsWith("#")) throw error(line, "unexpected text after the quoted value");
                        return value;
                    }
                    if (at >= lines.size()) throw error(line, "the quoted value never ends");
                    Line more = lines.get(at++);
                    joined.append('\n').append(more.raw());
                }
            }
            if (rest.equals("{}")) return new LinkedHashMap<String, Object>();
            if (rest.equals("[]")) return new ArrayList<>();
            if (c == '{' || c == '[') throw error(line, "write this as one 'key: value' per line (flow style is not read here)");
            if (c == '|' || c == '>') throw error(line, "block text (| or >) is not read here; put the value on one line in quotes");
            if (c == '&' || c == '*' || c == '!') throw error(line, "anchors, aliases and tags are not read here");
            StringBuilder plain = new StringBuilder(stripComment(rest));
            // A plain scalar continues on lines indented more than its key.
            while (true) {
                Line more = next();
                if (more == null || more.indent() <= indent) break;
                at++;
                plain.append(' ').append(stripComment(more.text()));
            }
            String value = plain.toString().strip();
            return value.equals("~") || value.equals("null") || value.equals("Null") || value.equals("NULL") ? null : value;
        }

        private static String stripComment(String text) {
            for (int i = 1; i < text.length(); i++) {
                if (text.charAt(i) == '#' && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) return text.substring(0, i).strip();
            }
            return text.strip();
        }

        /**
         * The quoted scalar starting at text[from]; stop[0] is set past its closing quote, or -1 when the
         * text ends first. Line breaks inside fold to a space (an empty line to a newline), as YAML does.
         */
        private String quoted(Line line, String text, int from, int[] stop) {
            char quote = text.charAt(from);
            StringBuilder out = new StringBuilder();
            int i = from + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\n') {
                    // fold: trailing spaces dropped, leading spaces of the next line dropped
                    int len = out.length();
                    while (len > 0 && (out.charAt(len - 1) == ' ' || out.charAt(len - 1) == '\t')) len--;
                    out.setLength(len);
                    int j = i + 1;
                    int breaks = 0;
                    while (true) {
                        while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) j++;
                        if (j < text.length() && text.charAt(j) == '\n') {
                            breaks++;
                            j++;
                        } else {
                            break;
                        }
                    }
                    out.append(breaks == 0 ? " " : "\n".repeat(breaks));
                    i = j;
                    continue;
                }
                if (quote == '\'') {
                    if (c == '\'') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                            out.append('\'');
                            i += 2;
                            continue;
                        }
                        stop[0] = i + 1;
                        return out.toString();
                    }
                    out.append(c);
                    i++;
                    continue;
                }
                if (c == '"') {
                    stop[0] = i + 1;
                    return out.toString();
                }
                if (c == '\\') {
                    if (i + 1 >= text.length()) break;
                    char e = text.charAt(i + 1);
                    i += 2;
                    switch (e) {
                        case '\n' -> {
                            // escaped line break: joined without a space
                            while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
                        }
                        case '0' -> out.append('\0');
                        case 'a' -> out.append('\u0007');
                        case 'b' -> out.append('\b');
                        case 't', '\t' -> out.append('\t');
                        case 'n' -> out.append('\n');
                        case 'v' -> out.append('\u000B');
                        case 'f' -> out.append('\f');
                        case 'r' -> out.append('\r');
                        case 'e' -> out.append('\u001B');
                        case ' ' -> out.append(' ');
                        case '"' -> out.append('"');
                        case '/' -> out.append('/');
                        case '\\' -> out.append('\\');
                        case 'N' -> out.append('\u0085');
                        case '_' -> out.append(' ');
                        case 'L' -> out.append(' ');
                        case 'P' -> out.append(' ');
                        case 'x', 'u', 'U' -> {
                            int n = e == 'x' ? 2 : e == 'u' ? 4 : 8;
                            if (i + n > text.length()) throw error(line, "a short \\" + e + " escape");
                            try {
                                out.appendCodePoint(Integer.parseInt(text.substring(i, i + n), 16));
                            } catch (IllegalArgumentException ex) {
                                throw error(line, "a bad \\" + e + " escape");
                            }
                            i += n;
                        }
                        default -> throw error(line, "unknown escape \\" + e);
                    }
                    continue;
                }
                out.append(c);
                i++;
            }
            stop[0] = -1;
            return out.toString();
        }
    }
}
