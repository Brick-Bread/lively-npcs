package dev.livelynpcs.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.livelynpcs.anim.Animation;
import dev.livelynpcs.npc.NpcDef;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The editor's JSON: NpcDef and Animation to and from it, with every check a person gets a clear
 * message for, and the checks on skin requests.
 */
final class EditorJson {

    /** Longest animation the editor accepts, in ticks (20 minutes), and most keyframes. */
    static final int MAX_TICKS = 24_000, MAX_KEYFRAMES = 2_000;
    /** Biggest uploaded skin image. */
    static final int MAX_SKIN_BYTES = 1024 * 1024;
    private static final double MAX_COORD = 30_000_000;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private EditorJson() {
    }

    /** What an NPC's fields are checked against. */
    record NpcChecks(Predicate<String> world, Predicate<String> skin, Set<String> items, Predicate<String> animation, Set<String> mobs) {
    }

    // ---- NPCs ----

    /** An NpcDef as JSON: exactly its record's fields, in their order. */
    static Map<String, Object> npc(NpcDef n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("entity", n.entity());
        m.put("variant", n.variant());
        m.put("world", n.world());
        m.put("x", n.x());
        m.put("y", n.y());
        m.put("z", n.z());
        m.put("yaw", n.yaw());
        m.put("skin", n.skin());
        m.put("plate", n.plate());
        m.put("icon", n.icon());
        m.put("held", n.held());
        m.put("click", n.click());
        m.put("idle", n.idle());
        m.put("greet", n.greet());
        m.put("clicked", n.clicked());
        m.put("beckon", n.beckon());
        m.put("look", n.look());
        return m;
    }

    /**
     * An NPC from the editor's JSON. A field left out keeps the stored NPC's value (old), or the
     * default for a new one; the id must be the one in the address.
     */
    static NpcDef readNpc(String pathId, JsonElement body, NpcDef old, NpcChecks c) throws ApiException {
        if (!(body instanceof JsonObject o)) throw ApiException.bad("The NPC must be a JSON object.");
        String id = text(o, "id", null);
        if (id == null) throw ApiException.bad("The NPC's id is missing.");
        if (!id.equals(pathId)) throw ApiException.bad("The NPC's id (" + id + ") does not match the address (" + pathId + ").");

        String entity = text(o, "entity", old != null ? old.entity() : "player").trim().toLowerCase(Locale.ROOT);
        if (entity.startsWith("minecraft:")) entity = entity.substring("minecraft:".length());
        if (entity.isEmpty()) entity = "player";
        if (!entity.equals("player") && !c.mobs().isEmpty() && !c.mobs().contains(entity)) {
            throw ApiException.bad("'" + entity + "' is not a mob an NPC can be: use player or a mob type such as villager.");
        }
        String variant = limit("variant", text(o, "variant", old != null ? old.variant() : ""), 64);

        String world = text(o, "world", old != null ? old.world() : null);
        if (world == null || world.isBlank()) throw ApiException.bad("Which world it stands in (world) is missing.");
        if (!c.world().test(world)) throw ApiException.bad("There is no world called '" + world + "' on this server.");
        double x = coordinate(o, "x", old != null ? old.x() : Double.NaN);
        double y = coordinate(o, "y", old != null ? old.y() : Double.NaN);
        double z = coordinate(o, "z", old != null ? old.z() : Double.NaN);
        double yawIn = number(o, "yaw", old != null ? old.yaw() : 0);
        if (!Double.isFinite(yawIn)) throw ApiException.bad("Which way it faces (yaw) must be a number.");
        float yaw = (float) normalizeYaw(yawIn);

        String skin = text(o, "skin", old != null ? old.skin() : "").trim();
        if (!skin.isEmpty() && !c.skin().test(skin)) throw ApiException.bad("There is no stored skin '" + skin + "': add the skin first.");

        List<String> plate = texts(o, "plate", old != null ? old.plate() : List.of("<bold>" + id), 16, 256, "name plate line");
        String icon = item(o, "icon", old != null ? old.icon() : "", c, "The icon");
        String held = item(o, "held", old != null ? old.held() : "", c, "The held item");

        List<String> click = new ArrayList<>();
        int n = 0;
        for (String line : texts(o, "click", old != null ? old.click() : List.of(), 32, 512, "click action")) {
            n++;
            int colon = line.indexOf(':');
            String type = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
            String rest = colon > 0 ? line.substring(colon + 1).trim() : "";
            if (!type.equals("player") && !type.equals("console") && !type.equals("message")) {
                throw ApiException.bad("Click action " + n + " ('" + line + "') must start with player:, console: or message:.");
            }
            if (!type.equals("message")) while (rest.startsWith("/")) rest = rest.substring(1);
            if (rest.isEmpty()) throw ApiException.bad("Click action " + n + " (" + type + ":) does not say what to " + (type.equals("message") ? "send." : "run."));
            click.add(type + ":" + rest);
        }

        List<String> idle = texts(o, "idle", old != null ? old.idle() : List.of(), 64, 40, "idle animation");
        if (idle.contains("none") && idle.size() > 1) throw ApiException.bad("Idle animations: 'none' means no idle animations, so it goes on its own.");
        for (String a : idle) {
            if (!a.equals("none") && !c.animation().test(a)) throw ApiException.bad("There is no animation called '" + a + "' (in its idle animations).");
        }
        String greet = animation(o, "greet", old != null ? old.greet() : NpcDef.DEFAULT_GREET, c, "for greeting");
        String clicked = animation(o, "clicked", old != null ? old.clicked() : NpcDef.DEFAULT_CLICKED, c, "for when it is clicked");
        String beckon = animation(o, "beckon", old != null ? old.beckon() : NpcDef.DEFAULT_BECKON, c, "for beckoning");
        boolean look = bool(o, "look", old == null || old.look());
        try {
            return new NpcDef(id, entity, variant, world, x, y, z, yaw, skin, plate, icon, held, click, idle, greet, clicked, beckon, look);
        } catch (IllegalArgumentException e) {
            throw ApiException.bad(e.getMessage());
        }
    }

    /** A yaw in -180 (exclusive) to 180, as Minecraft shows it. */
    static double normalizeYaw(double yaw) {
        double y = yaw % 360;
        if (y <= -180) y += 360;
        if (y > 180) y -= 360;
        return y;
    }

    private static String text(JsonObject o, String key, String def) throws ApiException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        if (e instanceof JsonPrimitive p && p.isString()) return p.getAsString();
        throw ApiException.bad("'" + key + "' must be text.");
    }

    private static String limit(String what, String value, int max) throws ApiException {
        if (value.length() > max) throw ApiException.bad("'" + what + "' is longer than " + max + " characters.");
        return value;
    }

    private static double number(JsonObject o, String key, double def) throws ApiException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        if (e instanceof JsonPrimitive p && p.isNumber()) return p.getAsDouble();
        throw ApiException.bad("'" + key + "' must be a number.");
    }

    private static double coordinate(JsonObject o, String key, double def) throws ApiException {
        double v = number(o, key, def);
        if (Double.isNaN(def) && (o.get(key) == null || o.get(key).isJsonNull())) throw ApiException.bad("Where it stands (" + key + ") is missing.");
        if (!Double.isFinite(v) || Math.abs(v) > MAX_COORD) throw ApiException.bad("'" + key + "' must be a number inside the world.");
        return v;
    }

    private static boolean bool(JsonObject o, String key, boolean def) throws ApiException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        if (e instanceof JsonPrimitive p && p.isBoolean()) return p.getAsBoolean();
        throw ApiException.bad("'" + key + "' must be true or false.");
    }

    private static List<String> texts(JsonObject o, String key, List<String> def, int maxItems, int maxLength, String what) throws ApiException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        if (!(e instanceof JsonArray a)) throw ApiException.bad("'" + key + "' must be a list of text.");
        if (a.size() > maxItems) throw ApiException.bad("At most " + maxItems + " " + what + "s.");
        List<String> out = new ArrayList<>();
        for (JsonElement item : a) {
            if (!(item instanceof JsonPrimitive p && p.isString())) throw ApiException.bad("Every " + what + " must be text.");
            String s = p.getAsString();
            if (s.length() > maxLength) throw ApiException.bad("A " + what + " is longer than " + maxLength + " characters.");
            out.add(s);
        }
        return out;
    }

    private static String item(JsonObject o, String key, String def, NpcChecks c, String what) throws ApiException {
        String id = text(o, key, def).trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
        if (id.isEmpty() || id.equals("air")) return "";
        if (!c.items().isEmpty() && !c.items().contains(id)) throw ApiException.bad(what + " '" + id + "' is not an item in this game.");
        return id;
    }

    private static String animation(JsonObject o, String key, String def, NpcChecks c, String what) throws ApiException {
        String id = text(o, key, def).trim();
        if (!id.isEmpty() && !c.animation().test(id)) throw ApiException.bad("There is no animation called '" + id + "' (" + what + ").");
        return id;
    }

    // ---- animations ----

    /** An animation as the editor's JSON: id, builtin, edited, then Animation.toMap(). */
    static Map<String, Object> animation(Animation a, Animation preset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("builtin", preset != null || a.builtin());
        m.put("edited", preset != null && !preset.toMap().equals(a.toMap()));
        m.putAll(a.toMap());
        return m;
    }

    /**
     * An animation from the editor's JSON (Animation.toMap()'s shape; an id in it is ignored). strict:
     * a value that cannot be read is an error rather than skipped.
     */
    static Animation readAnimation(String id, JsonElement body, boolean strict) throws ApiException {
        if (!(body instanceof JsonObject o)) throw ApiException.bad("The animation must be a JSON object.");
        JsonElement keys = o.get("keyframes");
        if (!(keys instanceof JsonArray list)) throw ApiException.bad("The animation has no keyframes list.");
        if (list.size() > MAX_KEYFRAMES) throw ApiException.bad("An animation can have at most " + MAX_KEYFRAMES + " keyframes.");
        int i = 0;
        for (JsonElement k : list) {
            i++;
            if (!(k instanceof JsonObject km)) throw ApiException.bad("Keyframe " + i + " is not a keyframe (a JSON object).");
            JsonElement at = km.get("at");
            if (!(at instanceof JsonPrimitive p && p.isNumber())) throw ApiException.bad("Keyframe " + i + " has no tick (at).");
            double t = p.getAsDouble();
            if (t != Math.rint(t) || t < 0 || t > MAX_TICKS) {
                throw ApiException.bad("Keyframe " + i + ": its tick must be a whole number from 0 to " + MAX_TICKS + " (it is " + p.getAsString() + ").");
            }
        }
        JsonElement length = o.get("length");
        if (length != null && !length.isJsonNull()) {
            if (!(length instanceof JsonPrimitive p && p.isNumber())) throw ApiException.bad("The length must be a number of ticks.");
            double l = p.getAsDouble();
            if (l != Math.rint(l) || l < 1 || l > MAX_TICKS) throw ApiException.bad("The length must be a whole number of ticks from 1 to " + MAX_TICKS + ".");
        }
        JsonElement name = o.get("name");
        if (name != null && !name.isJsonNull()) {
            if (!(name instanceof JsonPrimitive p && p.isString())) throw ApiException.bad("The name must be text.");
            if (p.getAsString().length() > 64) throw ApiException.bad("The name is longer than 64 characters.");
        }
        for (String flag : List.of("loop", "glance")) {
            JsonElement f = o.get(flag);
            if (f != null && !f.isJsonNull() && !(f instanceof JsonPrimitive p && p.isBoolean())) throw ApiException.bad("'" + flag + "' must be true or false.");
        }
        // The editor's own fields beside the map are not animation values.
        JsonObject copy = o.deepCopy();
        copy.remove("id");
        copy.remove("builtin");
        copy.remove("edited");
        Map<?, ?> map = WebServer.gson().fromJson(copy, Map.class);
        List<String> problems = new ArrayList<>();
        Animation a;
        try {
            a = Animation.fromMap(id, map, false, problems);
        } catch (IllegalArgumentException e) {
            throw ApiException.bad(e.getMessage());
        }
        if (strict && !problems.isEmpty()) throw ApiException.bad("Some values could not be read: " + String.join("; ", problems) + ".");
        return a;
    }

    // ---- skins ----

    static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** A skin image link: http or https, with a host. */
    static String skinUrl(String value) throws ApiException {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) throw ApiException.bad("Paste a link to the skin image.");
        if (url.length() > 2048) throw ApiException.bad("That link is too long.");
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) return url;
        } catch (Exception e) {
            // falls through
        }
        throw ApiException.bad("'" + url + "' is not a web link (http:// or https://).");
    }

    /** An uploaded skin (base64, or a data: link) as PNG bytes, checked: a PNG, 64x64 or 64x32, not huge. */
    static byte[] skinUpload(String value) throws ApiException {
        String data = value == null ? "" : value.trim();
        if (data.startsWith("data:")) {
            int comma = data.indexOf(',');
            data = comma < 0 ? "" : data.substring(comma + 1);
        }
        if (data.isEmpty()) throw ApiException.bad("The upload is empty: choose a skin image.");
        if (data.length() > MAX_SKIN_BYTES / 3 * 4 + 16) throw ApiException.bad("The image is bigger than 1 MB: a skin is a few kilobytes.");
        byte[] png;
        try {
            png = Base64.getDecoder().decode(data.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw ApiException.bad("The upload is not an image (expected base64 PNG data).");
        }
        if (png.length > MAX_SKIN_BYTES) throw ApiException.bad("The image is bigger than 1 MB: a skin is a few kilobytes.");
        if (png.length < 24 || !java.util.Arrays.equals(png, 0, 8, PNG, 0, 8)) throw ApiException.bad("The upload is not a PNG image.");
        int w = intAt(png, 16), h = intAt(png, 20);
        if (w != 64 || (h != 64 && h != 32)) throw ApiException.bad("The image is " + w + "x" + h + ": a skin is 64x64 (or an old 64x32).");
        return png;
    }

    private static int intAt(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }
}
