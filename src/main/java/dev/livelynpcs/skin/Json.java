package dev.livelynpcs.skin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer on the JDK alone, so the pack export (ExportCli) runs without the
 * server's libraries. Objects read as LinkedHashMap&lt;String, Object&gt; (key order kept), arrays as
 * List&lt;Object&gt;, numbers as Double, plus String, Boolean and null. The writer takes the same
 * types (any Number) and writes whole numbers without ".0", the way the pack's JSON files want them.
 */
public final class Json {

    private Json() {
    }

    /** Parses text as one JSON value; throws IllegalArgumentException (with the position) when it is not JSON. */
    public static Object parse(String text) {
        Reader r = new Reader(text);
        r.space();
        Object value = r.value();
        r.space();
        if (r.at < text.length()) throw r.error("unexpected text after the value");
        return value;
    }

    /** Parses text that must be a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        return (Map<String, Object>) value;
    }

    /** value[path...] where each step is an object key; null when any step is missing or not an object. */
    @SuppressWarnings("unchecked")
    public static Object at(Object value, String... path) {
        Object v = value;
        for (String key : path) {
            if (!(v instanceof Map)) return null;
            v = ((Map<String, Object>) v).get(key);
        }
        return v;
    }

    /** The string at path, or null when missing or not a string. */
    public static String string(Object value, String... path) {
        return at(value, path) instanceof String s ? s : null;
    }

    /** The number at path, or the fallback when missing or not a number. */
    public static double number(Object value, double fallback, String... path) {
        return at(value, path) instanceof Number n ? n.doubleValue() : fallback;
    }

    /** The list at path, or an empty list. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value, String... path) {
        return at(value, path) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** Compact JSON. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, -1, 0);
        return out.toString();
    }

    /**
     * Indented JSON (two spaces), with arrays of plain values on one line, e.g. "from": [4, 0, 4], and
     * a newline at the end: the look of the pack's files.
     */
    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 2, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, int indent, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(out, s);
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof Number n) {
            out.append(number(n));
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                newline(out, indent, depth + 1);
                quote(out, String.valueOf(e.getKey()));
                out.append(indent < 0 ? ":" : ": ");
                write(out, e.getValue(), indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            boolean flat = list.stream().noneMatch(v -> v instanceof Map || v instanceof List);
            out.append('[');
            boolean first = true;
            for (Object v : list) {
                if (!first) out.append(indent >= 0 && flat ? ", " : ",");
                first = false;
                if (!flat) newline(out, indent, depth + 1);
                write(out, v, indent, depth + 1);
            }
            if (!flat) newline(out, indent, depth);
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent < 0) return;
        out.append('\n');
        out.append(" ".repeat(indent * depth));
    }

    /** A number as JSON: 8 rather than 8.0, 5.75 as it is. */
    static String number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return n.toString();
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("JSON has no " + d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7F || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Writes s as a JSON string (with the quotes), for other writers (skins.yml uses the same quoting). */
    static String quoted(String s) {
        StringBuilder out = new StringBuilder();
        quote(out, s);
        return out.toString();
    }

    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("not JSON: " + what + " at character " + at);
        }

        void space() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') at++;
                else break;
            }
        }

        Object value() {
            if (at >= text.length()) throw error("unexpected end");
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("unexpected '" + c + "'");
                }
            };
        }

        Object word(String word, Object value) {
            if (!text.startsWith(word, at)) throw error("expected " + word);
            at += word.length();
            return value;
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++; // {
            space();
            if (at < text.length() && text.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                space();
                if (at >= text.length() || text.charAt(at) != '"') throw error("expected a key");
                String key = string();
                space();
                if (at >= text.length() || text.charAt(at) != ':') throw error("expected ':'");
                at++;
                space();
                map.put(key, value());
                space();
                if (at >= text.length()) throw error("unexpected end in an object");
                char c = text.charAt(at++);
                if (c == '}') return map;
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++; // [
            space();
            if (at < text.length() && text.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                space();
                list.add(value());
                space();
                if (at >= text.length()) throw error("unexpected end in an array");
                char c = text.charAt(at++);
                if (c == ']') return list;
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        String string() {
            at++; // "
            StringBuilder out = new StringBuilder();
            while (true) {
                if (at >= text.length()) throw error("unterminated string");
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (at >= text.length()) throw error("unterminated escape");
                    char e = text.charAt(at++);
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (at + 4 > text.length()) throw error("short \\u escape");
                            try {
                                out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad \\u escape");
                            }
                            at += 4;
                        }
                        default -> throw error("bad escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw error("control character in a string");
                } else {
                    out.append(c);
                }
            }
        }

        Double number() {
            int start = at;
            if (text.charAt(at) == '-') at++;
            while (at < text.length()) {
                char c = text.charAt(at);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') at++;
                else break;
            }
            try {
                return Double.parseDouble(text.substring(start, at));
            } catch (NumberFormatException e) {
                at = start;
                throw error("bad number");
            }
        }
    }
}
