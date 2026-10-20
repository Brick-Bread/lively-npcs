package dev.livelynpcs.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.anim.Animation;
import dev.livelynpcs.anim.AnimationService;
import dev.livelynpcs.anim.Channel;
import dev.livelynpcs.anim.Easing;
import dev.livelynpcs.npc.NpcDef;
import dev.livelynpcs.npc.NpcService;
import dev.livelynpcs.pack.PackService;
import dev.livelynpcs.skin.Skin;
import dev.livelynpcs.skin.SkinService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The web editor's JSON API under /api/ (DESIGN.md "Web"): every call needs the editor session's
 * Bearer token, hops to the main thread before it touches NPCs, animations, skins or players, and
 * answers {"error": "..."} for people when something is wrong.
 *
 * <pre>
 * GET    /api/state                     everything the page shows
 * PUT    /api/npcs/{id}                 save an NPC (NpcDef JSON)
 * POST   /api/npcs                      {id, copyFrom?}: a new NPC where the editor stands, or a copy
 * DELETE /api/npcs/{id}
 * POST   /api/npcs/{id}/move-to-me
 * PUT    /api/animations/{id}           save an animation (Animation.toMap() JSON)
 * DELETE /api/animations/{id}           delete it (a preset goes back to how it ships)
 * POST   /api/preview                   {npc, animation: {id, ...}}: play a draft for the editor's player
 * POST   /api/preview/stop
 * POST   /api/play                      {npc, animation, everyone}
 * POST   /api/skins                     {type: player|url|upload, value, slim}: waits up to 60 s
 * GET    /api/skins/{key}.png           the skin image, for the 3D view
 * </pre>
 */
public final class EditorApi {

    /** How long adding a skin may wait for Mojang or MineSkin. */
    static final long SKIN_TIMEOUT_MILLIS = 60_000;

    private final EditorHost host;
    private final WebServer web;
    private final NpcService npcs;
    private final AnimationService animations;
    private final SkinService skins;
    private final long skinTimeoutMillis;
    private volatile Set<String> itemSet;

    public EditorApi(LivelyNpcs plugin, WebServer web, NpcService npcs, AnimationService animations, SkinService skins,
                     PackService pack) {
        this(new EditorHost.Server(plugin, web), web, npcs, animations, skins, SKIN_TIMEOUT_MILLIS);
    }

    EditorApi(EditorHost host, WebServer web, NpcService npcs, AnimationService animations, SkinService skins, long skinTimeoutMillis) {
        this.host = host;
        this.web = web;
        this.npcs = npcs;
        this.animations = animations;
        this.skins = skins;
        this.skinTimeoutMillis = skinTimeoutMillis;
    }

    /** Registers /api/ on the web server; called once on enable when the web server runs. */
    public void register() {
        web.handle("/api/", this::handle);
    }

    /** One answer: JSON, or bytes of a type, with extra headers. */
    record Reply(int status, Object json, byte[] bytes, String type, Map<String, String> headers) {
        static Reply json(int status, Object body) {
            return new Reply(status, body, null, null, Map.of());
        }
    }

    /** Handles one /api/ request. */
    void handle(HttpExchange exchange) throws IOException {
        Reply reply;
        try {
            WebServer.Session session = host.session(exchange);
            if (session == null) {
                throw new ApiException(401, "Your editor link has expired or is not valid: run /npc editor again in game for a new one.");
            }
            reply = route(exchange, session);
        } catch (ApiException e) {
            reply = error(e.status, e.getMessage());
        } catch (IllegalArgumentException e) {
            reply = error(400, e.getMessage() == null ? "That was not accepted." : e.getMessage());
        } catch (IOException e) {
            host.warn("Editor " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + ": " + e);
            reply = error(500, "Could not save it: " + e.getMessage());
        } catch (Exception e) {
            host.warn("Editor " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " failed: " + e);
            reply = error(500, "Something went wrong on the server: " + e);
        }
        for (Map.Entry<String, String> h : reply.headers().entrySet()) exchange.getResponseHeaders().set(h.getKey(), h.getValue());
        if (reply.bytes() != null) WebServer.bytes(exchange, reply.status(), reply.type(), reply.bytes());
        else WebServer.json(exchange, reply.status(), reply.json());
    }

    private static Reply error(int status, String message) {
        return Reply.json(status, Map.of("error", message));
    }

    private Reply route(HttpExchange exchange, WebServer.Session session) throws Exception {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        List<String> p = segments(exchange.getRequestURI().getRawPath());
        String first = p.isEmpty() ? "" : p.getFirst();
        switch (first) {
            case "state" -> {
                if (p.size() == 1) {
                    allow(method, "GET");
                    return state(session);
                }
            }
            case "npcs" -> {
                if (p.size() == 1) {
                    allow(method, "POST");
                    return createNpc(exchange, session);
                }
                if (p.size() == 2) {
                    return switch (method) {
                        case "PUT" -> putNpc(exchange, p.get(1));
                        case "DELETE" -> deleteNpc(p.get(1));
                        default -> throw notAllowed("PUT or DELETE");
                    };
                }
                if (p.size() == 3 && p.get(2).equals("move-to-me")) {
                    allow(method, "POST");
                    return moveToMe(p.get(1), session);
                }
            }
            case "animations" -> {
                if (p.size() == 2) {
                    return switch (method) {
                        case "PUT" -> putAnimation(exchange, p.get(1));
                        case "DELETE" -> deleteAnimation(p.get(1));
                        default -> throw notAllowed("PUT or DELETE");
                    };
                }
            }
            case "preview" -> {
                if (p.size() == 1) {
                    allow(method, "POST");
                    return preview(exchange, session);
                }
                if (p.size() == 2 && p.get(1).equals("stop")) {
                    allow(method, "POST");
                    return stopPreview(session);
                }
            }
            case "play" -> {
                if (p.size() == 1) {
                    allow(method, "POST");
                    return play(exchange, session);
                }
            }
            case "skins" -> {
                if (p.size() == 1) {
                    allow(method, "POST");
                    return addSkin(exchange);
                }
                if (p.size() == 2 && p.get(1).endsWith(".png")) {
                    if (!method.equals("GET") && !method.equals("HEAD")) throw notAllowed("GET");
                    return skinImage(p.get(1).substring(0, p.get(1).length() - 4));
                }
            }
            default -> {
                // falls through to not found
            }
        }
        throw ApiException.notFound("There is no editor API call " + method + " /api/" + String.join("/", p) + ".");
    }

    private static List<String> segments(String rawPath) {
        String rest = rawPath.startsWith("/api/") ? rawPath.substring(5) : rawPath;
        List<String> out = new ArrayList<>();
        for (String s : rest.split("/")) {
            if (!s.isEmpty()) out.add(URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void allow(String method, String allowed) throws ApiException {
        if (!method.equals(allowed)) throw notAllowed(allowed);
    }

    private static ApiException notAllowed(String allowed) {
        return new ApiException(405, "Use " + allowed + " for this API call.");
    }

    // ---- reading requests ----

    private static JsonElement read(HttpExchange exchange) throws ApiException {
        byte[] raw;
        try {
            raw = WebServer.body(exchange, WebServer.MAX_BODY);
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("bigger than")) {
                throw new ApiException(413, "The request is too big (more than " + WebServer.MAX_BODY / 1024 / 1024 + " MB).");
            }
            throw ApiException.bad("The request could not be read: " + e.getMessage());
        }
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) throw ApiException.bad("The request is empty: it should be JSON.");
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw ApiException.bad("The request is not JSON: " + e.getMessage());
        }
    }

    private static JsonObject object(HttpExchange exchange) throws ApiException {
        if (read(exchange) instanceof JsonObject o) return o;
        throw ApiException.bad("The request must be a JSON object.");
    }

    private static String string(JsonObject o, String key, boolean required) throws ApiException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            if (required) throw ApiException.bad("'" + key + "' is missing.");
            return null;
        }
        if (e instanceof JsonPrimitive p && p.isString()) return p.getAsString().trim();
        throw ApiException.bad("'" + key + "' must be text.");
    }

    private static boolean flag(JsonObject o, String key) throws ApiException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return false;
        if (e instanceof JsonPrimitive p && p.isBoolean()) return p.getAsBoolean();
        throw ApiException.bad("'" + key + "' must be true or false.");
    }

    private static void npcId(String id) throws ApiException {
        if (!NpcDef.ID.matcher(id).matches()) throw ApiException.bad("'" + id + "' is not an NPC id: use 1 to 40 of a-z, 0-9, _ and -.");
    }

    private static void animationId(String id) throws ApiException {
        if (!Animation.ID.matcher(id).matches()) throw ApiException.bad("'" + id + "' is not an animation id: use 1 to 40 of a-z, 0-9, _ and -.");
    }

    // ---- state ----

    private Reply state(WebServer.Session session) throws Exception {
        Map<String, Object> body = host.sync(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("player", player(session));
            List<Object> npcList = new ArrayList<>();
            for (NpcDef n : npcs.all()) npcList.add(EditorJson.npc(n));
            m.put("npcs", npcList);
            List<Object> animList = new ArrayList<>();
            for (Animation a : animations.all()) animList.add(EditorJson.animation(a, animations.preset(a.id())));
            m.put("animations", animList);
            List<Object> skinList = new ArrayList<>();
            for (Skin s : skins.all()) skinList.add(skin(s));
            m.put("skins", skinList);
            m.put("items", host.items());
            m.put("channels", channels());
            m.put("easings", easings());
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("scale", host.scale());
            settings.put("keyTicks", host.keyTicks());
            m.put("settings", settings);
            return m;
        });
        body.put("session", Map.of("expiresIn", Math.max(0, session.expiresAt() - System.currentTimeMillis())));
        return Reply.json(200, body);
    }

    private Map<String, Object> player(WebServer.Session session) {
        Player p = host.player(session.player());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p != null ? p.getName() : Objects.requireNonNullElse(host.playerName(session.player()), ""));
        m.put("uuid", session.player().toString());
        m.put("online", p != null);
        m.put("world", p != null ? p.getWorld().getName() : "");
        return m;
    }

    static List<Map<String, Object>> channels() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Channel c : Channel.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key);
            m.put("pixels", c.pixels);
            m.put("min", c.min);
            m.put("max", c.max);
            list.add(m);
        }
        return list;
    }

    static List<String> easings() {
        List<String> list = new ArrayList<>();
        for (Easing e : Easing.values()) list.add(e.key);
        return list;
    }

    private static Map<String, Object> skin(Skin s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.key());
        m.put("slim", s.slim());
        m.put("source", s.source());
        return m;
    }

    // ---- NPCs ----

    private EditorJson.NpcChecks checks() {
        Set<String> items = itemSet;
        if (items == null) {
            items = new HashSet<>(host.items());
            itemSet = items;
        }
        return new EditorJson.NpcChecks(host::worldExists, key -> skins.get(key) != null, items, id -> animations.get(id) != null,
                host.mobTypes());
    }

    private Reply putNpc(HttpExchange exchange, String id) throws Exception {
        npcId(id);
        JsonElement body = read(exchange);
        NpcDef stored = host.sync(() -> {
            NpcDef def = EditorJson.readNpc(id, body, npcs.get(id), checks());
            npcs.put(def);
            return Objects.requireNonNullElse(npcs.get(id), def);
        });
        return Reply.json(200, EditorJson.npc(stored));
    }

    private Reply createNpc(HttpExchange exchange, WebServer.Session session) throws Exception {
        JsonObject body = object(exchange);
        String id = string(body, "id", true);
        npcId(id);
        String copyFrom = string(body, "copyFrom", false);
        NpcDef made = host.sync(() -> {
            if (npcs.get(id) != null) throw ApiException.conflict("There is already an NPC called " + id + ".");
            NpcDef def;
            if (copyFrom != null && !copyFrom.isEmpty()) {
                NpcDef from = npcs.get(copyFrom);
                if (from == null) throw ApiException.notFound("There is no NPC called " + copyFrom + " to copy.");
                def = new NpcDef(id, from.entity(), from.variant(), from.world(), from.x(), from.y(), from.z(), from.yaw(), from.skin(),
                        from.plate(), from.icon(), from.held(), from.click(), from.idle(), from.greet(), from.clicked(), from.beckon(), from.look());
            } else {
                Location at = whereTheEditorStands(session, "a new NPC stands where you are");
                def = NpcDef.create(id, at.getWorld().getName(), round(at.getX()), round(at.getY()), round(at.getZ()), yaw(at.getYaw()));
            }
            npcs.put(def);
            return Objects.requireNonNullElse(npcs.get(id), def);
        });
        return Reply.json(201, EditorJson.npc(made));
    }

    private Reply deleteNpc(String id) throws Exception {
        npcId(id);
        boolean removed = host.sync(() -> npcs.remove(id));
        if (!removed) throw ApiException.notFound("There is no NPC called " + id + ".");
        return Reply.json(200, Map.of("deleted", id));
    }

    private Reply moveToMe(String id, WebServer.Session session) throws Exception {
        npcId(id);
        NpcDef moved = host.sync(() -> {
            NpcDef n = npcs.get(id);
            if (n == null) throw ApiException.notFound("There is no NPC called " + id + ".");
            Location at = whereTheEditorStands(session, "it moves to where you are");
            NpcDef def = n.at(at.getWorld().getName(), round(at.getX()), round(at.getY()), round(at.getZ()), yaw(at.getYaw()));
            npcs.put(def);
            return Objects.requireNonNullElse(npcs.get(id), def);
        });
        return Reply.json(200, EditorJson.npc(moved));
    }

    /** The editor's player's spot (main thread); 409 when they are not in game. */
    private Location whereTheEditorStands(WebServer.Session session, String why) throws ApiException {
        Player p = host.player(session.player());
        if (p == null) throw ApiException.conflict("You are not in game: join the server first, " + why + ".");
        Location at = p.getLocation();
        if (at.getWorld() == null) throw ApiException.conflict("Your world is not loaded: try again in a moment.");
        return at;
    }

    /** Blocks to 3 decimals, enough for npcs.yml. */
    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** A player's yaw as an NPC's: -180 to 180, to a tenth of a degree. */
    private static float yaw(float yaw) {
        return (float) (Math.round(EditorJson.normalizeYaw(yaw) * 10) / 10.0);
    }

    // ---- animations ----

    private Reply putAnimation(HttpExchange exchange, String id) throws Exception {
        animationId(id);
        Animation a = EditorJson.readAnimation(id, read(exchange), true);
        Map<String, Object> stored = host.sync(() -> {
            animations.save(a);
            Animation now = Objects.requireNonNullElse(animations.get(id), a);
            return EditorJson.animation(now, animations.preset(id));
        });
        return Reply.json(200, stored);
    }

    private Reply deleteAnimation(String id) throws Exception {
        animationId(id);
        Map<String, Object> result = host.sync(() -> {
            boolean preset = animations.preset(id) != null;
            if (!animations.delete(id)) {
                throw ApiException.notFound(preset ? id + " is a preset as it ships: there is nothing to restore."
                        : "There is no animation called " + id + ".");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deleted", id);
            Animation now = animations.get(id);
            if (now != null) m.put("animation", EditorJson.animation(now, animations.preset(id)));
            return m;
        });
        return Reply.json(200, result);
    }

    // ---- playing ----

    private Reply preview(HttpExchange exchange, WebServer.Session session) throws Exception {
        JsonObject body = object(exchange);
        String npc = string(body, "npc", true);
        if (!(body.get("animation") instanceof JsonObject draftJson)) throw ApiException.bad("'animation' must be the draft animation (a JSON object).");
        String draftId = draftJson.get("id") instanceof JsonPrimitive p && p.isString() && Animation.ID.matcher(p.getAsString()).matches()
                ? p.getAsString() : "preview";
        Animation draft = EditorJson.readAnimation(draftId, draftJson, false);
        host.sync(() -> {
            NpcDef n = npcs.get(npc);
            if (n == null) throw ApiException.notFound("There is no NPC called " + npc + ".");
            if (!n.person()) throw ApiException.conflict(mob(n));
            Player p = host.player(session.player());
            if (p == null) throw ApiException.conflict("You are not in game: join the server to see the live preview.");
            if (!npcs.preview(npc, draft, p)) throw ApiException.conflict("Could not play the preview on " + npc + ".");
            return null;
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playing", draft.id());
        m.put("npc", npc);
        m.put("loop", draft.loop());
        return Reply.json(200, m);
    }

    private Reply stopPreview(WebServer.Session session) throws Exception {
        host.sync(() -> {
            Player p = host.player(session.player());
            if (p != null) npcs.stopPreview(p);
            return null;
        });
        return Reply.json(200, Map.of("stopped", true));
    }

    private Reply play(HttpExchange exchange, WebServer.Session session) throws Exception {
        JsonObject body = object(exchange);
        String npc = string(body, "npc", true);
        String animation = string(body, "animation", true);
        boolean everyone = flag(body, "everyone");
        host.sync(() -> {
            NpcDef n = npcs.get(npc);
            if (n == null) throw ApiException.notFound("There is no NPC called " + npc + ".");
            if (!n.person()) throw ApiException.conflict(mob(n));
            if (animations.get(animation) == null) throw ApiException.notFound("There is no animation called " + animation + ": save it first.");
            Player p = null;
            if (!everyone) {
                p = host.player(session.player());
                if (p == null) throw ApiException.conflict("You are not in game: join the server to see it, or play it for everyone.");
            }
            if (!npcs.play(npc, animation, p)) throw ApiException.conflict("Could not play " + animation + " on " + npc + ".");
            return null;
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playing", animation);
        m.put("npc", npc);
        m.put("everyone", everyone);
        return Reply.json(200, m);
    }

    private static String mob(NpcDef n) {
        return n.id() + " is a " + n.entity().replace('_', ' ') + ": only person NPCs play animations.";
    }

    // ---- skins ----

    private Reply addSkin(HttpExchange exchange) throws Exception {
        JsonObject body = object(exchange);
        String type = Objects.requireNonNullElse(string(body, "type", true), "").toLowerCase(Locale.ROOT);
        String value = Objects.requireNonNullElse(string(body, "value", false), "");
        boolean slim = flag(body, "slim");
        CompletableFuture<Skin> future;
        switch (type) {
            case "player" -> {
                if (!EditorJson.PLAYER_NAME.matcher(value).matches()) {
                    throw ApiException.bad(value.isEmpty() ? "Type a player name." : "'" + value + "' is not a player name (1 to 16 letters, numbers or _).");
                }
                future = host.sync(() -> skins.fromPlayer(value));
            }
            case "url" -> {
                String url = EditorJson.skinUrl(value);
                future = host.sync(() -> skins.fromUrl(url, slim));
            }
            case "upload" -> {
                byte[] png = EditorJson.skinUpload(value);
                future = host.sync(() -> skins.fromUpload(png, slim));
            }
            default -> throw ApiException.bad("'" + type + "' is not a way to add a skin: use player, url or upload.");
        }
        if (future == null) throw new ApiException(500, "The skin service did not start.");
        Skin skin;
        try {
            skin = future.get(skinTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(504, "Getting the skin took longer than " + Math.max(1, skinTimeoutMillis / 1000)
                    + " seconds: try again in a moment.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "The server is shutting down: try again in a moment.");
        } catch (ExecutionException | CompletionException e) {
            Throwable cause = e.getCause();
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
            String message = cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                    ? "Could not get that skin." : cause.getMessage();
            throw ApiException.bad(message);
        }
        if (skin == null) throw new ApiException(502, "The skin service gave no skin back.");
        return Reply.json(200, skin(skin));
    }

    private Reply skinImage(String key) throws Exception {
        if (!Skin.KEY.matcher(key).matches()) throw ApiException.notFound("There is no skin called " + key + ".");
        Skin skin = host.sync(() -> skins.get(key));
        if (skin == null) throw ApiException.notFound("There is no stored skin " + key + ".");
        byte[] png;
        try {
            png = skins.png(skin);
        } catch (IOException e) {
            throw new ApiException(502, "Could not get the skin's picture: " + e.getMessage());
        }
        if (png == null || png.length == 0) throw new ApiException(502, "The skin's picture is empty.");
        // A key names one image for good, so the browser may keep it.
        return new Reply(200, null, png, "image/png", Map.of("Cache-Control", "private, max-age=86400"));
    }
}
