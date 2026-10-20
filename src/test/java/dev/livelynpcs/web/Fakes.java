package dev.livelynpcs.web;

import com.sun.net.httpserver.HttpExchange;
import dev.livelynpcs.anim.Animation;
import dev.livelynpcs.anim.AnimationService;
import dev.livelynpcs.npc.NpcDef;
import dev.livelynpcs.npc.NpcService;
import dev.livelynpcs.skin.Skin;
import dev.livelynpcs.skin.SkinService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** In-memory stand-ins for the server and the services, for EditorApi's tests. */
final class Fakes {

    private Fakes() {
    }

    /** Set while a task runs "on the main thread" (inside TestHost.sync). */
    static final ThreadLocal<Boolean> MAIN = ThreadLocal.withInitial(() -> false);

    static void onMain(String what) {
        if (!MAIN.get()) throw new AssertionError(what + " was called off the main thread");
    }

    static void offMain(String what) {
        if (MAIN.get()) throw new AssertionError(what + " was called on the main thread");
    }

    // ---- the server ----

    static final class TestHost implements EditorHost {
        static final String TOKEN = "test-token-0123456789abcdef";
        final UUID editor = UUID.fromString("3f2a9c0d-11e4-4b7a-9a10-6c5e2b7d8e91");
        long expiresAt = System.currentTimeMillis() + 3_600_000;
        Player online;
        String knownName = "Brick_Bread";
        final Set<String> worlds = new HashSet<>(Set.of("world", "world_nether"));
        final List<String> items = List.of("cow_spawn_egg", "diamond", "gold_ingot", "wheat");
        final Set<String> mobs = Set.of("villager", "copper_golem", "cow");
        final List<String> warnings = new ArrayList<>();
        int syncCalls;
        boolean busy;

        @Override
        public WebServer.Session session(HttpExchange exchange) {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + TOKEN).equals(header) || System.currentTimeMillis() >= expiresAt) return null;
            return new WebServer.Session(TOKEN, editor, expiresAt);
        }

        @Override
        public <T> T sync(Callable<T> task) throws Exception {
            syncCalls++;
            if (busy) throw new ApiException(503, "The server is too busy to answer right now: try again in a moment.");
            MAIN.set(true);
            try {
                return task.call();
            } finally {
                MAIN.set(false);
            }
        }

        @Override
        public Player player(UUID id) {
            onMain("player()");
            return id.equals(editor) ? online : null;
        }

        @Override
        public String playerName(UUID id) {
            onMain("playerName()");
            return id.equals(editor) ? knownName : null;
        }

        @Override
        public boolean worldExists(String name) {
            onMain("worldExists()");
            return worlds.contains(name);
        }

        @Override public List<String> items() { return items; }
        @Override public Set<String> mobTypes() { return mobs; }
        @Override public float scale() { return 0.9375f; }
        @Override public int keyTicks() { return 2; }
        @Override public void warn(String message) { warnings.add(message); }
    }

    static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, m, args) -> switch (m.getName()) {
            case "getName" -> name;
            case "hashCode" -> name.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "World[" + name + "]";
            default -> throw new UnsupportedOperationException("World." + m.getName());
        });
    }

    static Player player(UUID id, String name, World world, double x, double y, double z, float yaw) {
        Location at = new Location(world, x, y, z, yaw, 12);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, m, args) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> name;
            case "getLocation" -> at.clone();
            case "getWorld" -> world;
            case "isOnline" -> true;
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "Player[" + name + "]";
            default -> throw new UnsupportedOperationException("Player." + m.getName());
        });
    }

    // ---- NPCs ----

    static final class FakeNpcs implements NpcService {
        final TreeMap<String, NpcDef> map = new TreeMap<>();
        final List<String> log = new ArrayList<>();
        Animation previewed;
        Player previewedFor;
        Player playedFor;
        boolean playResult = true, previewResult = true, stopped;
        IOException failPut;
        IllegalArgumentException refusePut;

        @Override
        public Collection<NpcDef> all() {
            onMain("npcs.all()");
            return List.copyOf(map.values());
        }

        @Override
        public NpcDef get(String id) {
            onMain("npcs.get()");
            return map.get(id);
        }

        @Override
        public void put(NpcDef npc) throws IOException {
            onMain("npcs.put()");
            if (failPut != null) throw failPut;
            if (refusePut != null) throw refusePut;
            log.add("put " + npc.id());
            map.put(npc.id(), npc);
        }

        @Override
        public boolean remove(String id) {
            onMain("npcs.remove()");
            log.add("remove " + id);
            return map.remove(id) != null;
        }

        @Override
        public boolean play(String npcId, String animationId, Player player) {
            onMain("npcs.play()");
            log.add("play " + npcId + " " + animationId + " " + (player == null ? "everyone" : player.getName()));
            playedFor = player;
            return playResult;
        }

        @Override
        public boolean preview(String npcId, Animation draft, Player player) {
            onMain("npcs.preview()");
            log.add("preview " + npcId + " " + draft.id());
            previewed = draft;
            previewedFor = player;
            return previewResult;
        }

        @Override
        public void stopPreview(Player player) {
            onMain("npcs.stopPreview()");
            log.add("stop " + player.getName());
            stopped = true;
        }

        @Override public void reload() { }
        @Override public void addListener(Runnable onChange) { }
    }

    static NpcDef person(String id, String skin) {
        return new NpcDef(id, "player", "", "world", 6.5, 64, -2.5, 180, skin, List.of("<gold><bold>Mob Shop", "<gray>Buy mobs"),
                "cow_spawn_egg", "wheat", List.of("player:shop mobs"), List.of(), "greet", "talk", "beckon", true);
    }

    static NpcDef villager(String id) {
        return new NpcDef(id, "villager", "plains/farmer", "world", 12.5, 64, 8.5, 135, "", List.of("<green>Farmer"), "", "",
                List.of(), List.of(), "", "", "", true);
    }

    // ---- animations ----

    static final class FakeAnimations implements AnimationService {
        final TreeMap<String, Animation> presets = new TreeMap<>();
        final TreeMap<String, Animation> files = new TreeMap<>();
        IOException failSave;

        @Override
        public Collection<Animation> all() {
            onMain("animations.all()");
            TreeMap<String, Animation> all = new TreeMap<>(presets);
            all.putAll(files);
            return List.copyOf(all.values());
        }

        @Override
        public Animation get(String id) {
            onMain("animations.get()");
            return files.containsKey(id) ? files.get(id) : presets.get(id);
        }

        @Override
        public Animation preset(String id) {
            onMain("animations.preset()");
            return presets.get(id);
        }

        @Override
        public void save(Animation animation) throws IOException {
            onMain("animations.save()");
            if (failSave != null) throw failSave;
            files.put(animation.id(), animation);
        }

        @Override
        public boolean delete(String id) {
            onMain("animations.delete()");
            return files.remove(id) != null;
        }

        @Override public void reload() { }
        @Override public void addListener(Runnable onChange) { }
    }

    /** An animation from short key/value pairs per keyframe: anim("nod", 20, false, Map.of("at", 0), Map.of("at", 4, "head-pitch", 12)). */
    @SafeVarargs
    static Animation anim(String id, int length, boolean builtin, Map<String, Object>... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", id.substring(0, 1).toUpperCase() + id.substring(1));
        m.put("length", length);
        m.put("keyframes", List.of(keys));
        List<String> problems = new ArrayList<>();
        Animation a = Animation.fromMap(id, m, builtin, problems);
        if (!problems.isEmpty()) throw new IllegalStateException(problems.toString());
        return a;
    }

    // ---- skins ----

    static final class FakeSkins implements SkinService {
        final Map<String, Skin> map = new LinkedHashMap<>();
        byte[] uploaded;
        boolean failPng;
        final byte[] image = "PNG image bytes".getBytes(StandardCharsets.UTF_8);

        @Override
        public Skin get(String key) {
            onMain("skins.get()");
            return map.get(key);
        }

        @Override
        public Collection<Skin> all() {
            onMain("skins.all()");
            return List.copyOf(map.values());
        }

        @Override
        public CompletableFuture<Skin> fromPlayer(String name) {
            onMain("skins.fromPlayer()");
            if (name.equals("Nobody")) return CompletableFuture.failedFuture(new IllegalArgumentException("There is no player called Nobody."));
            if (name.equals("Slowpoke")) return new CompletableFuture<>();
            return CompletableFuture.completedFuture(store(skin("player:" + name, name.equals("Alex"), "player:" + name)));
        }

        @Override
        public CompletableFuture<Skin> fromUrl(String url, boolean slim) {
            onMain("skins.fromUrl()");
            if (url.contains("broken")) return CompletableFuture.failedFuture(new IllegalStateException("MineSkin could not read a skin at that link."));
            return CompletableFuture.completedFuture(store(skin(url, slim, "url:" + url)));
        }

        @Override
        public CompletableFuture<Skin> fromUpload(byte[] png, boolean slim) {
            onMain("skins.fromUpload()");
            uploaded = png;
            return CompletableFuture.completedFuture(store(skin(HexFormat.of().formatHex(png), slim, "upload")));
        }

        @Override
        public Skin fromSigned(String value, String signature, String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] png(Skin skin) throws IOException {
            offMain("skins.png()");
            if (failPng) throw new IOException("textures.minecraft.net did not answer");
            return image;
        }

        @Override public void reload() { }

        Skin store(Skin s) {
            map.put(s.key(), s);
            return s;
        }
    }

    static Skin skin(String seed, boolean slim, String source) {
        try {
            String texture = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
            return new Skin(texture.substring(0, 16), texture, "dmFsdWU=", "c2lnbmF0dXJl", slim, source);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A real PNG image of this size. */
    static byte[] png(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(8, 8, 0xff3366cc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
