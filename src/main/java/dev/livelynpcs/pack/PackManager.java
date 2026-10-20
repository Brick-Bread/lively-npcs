package dev.livelynpcs.pack;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientResourcePackStatus;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerResourcePackRemove;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerResourcePackSend;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackSend;
import com.sun.net.httpserver.HttpExchange;
import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.Settings;
import dev.livelynpcs.skin.Skin;
import dev.livelynpcs.skin.SkinImage;
import dev.livelynpcs.skin.SkinService;
import dev.livelynpcs.web.WebServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds, hosts, sends or exports the NPC pack, and knows who has it (DESIGN.md "Pack").
 *
 * <ul>
 * <li><b>own/builtin</b>: the pack is served at /pack/&lt;sha1&gt;.zip by the web server and offered to every
 *     player on join, beside any other server pack; a new build is offered to everyone online at once,
 *     the old version taken away by its id first.</li>
 * <li><b>own/url</b>: the same, from pack.url and pack.sha1. Which skins that pack holds is known from
 *     the builds made here (pack-builds.yml), so an old upload shows only the skins it has.</li>
 * <li><b>merged</b>: the models go to export/ for the server's own pack; a player has them once a server
 *     pack (any, or one whose link contains pack.merged-url-contains) has loaded, for the skins
 *     confirmed with /npc pack merged.</li>
 * </ul>
 * export/lively-npcs-pack.zip (the whole pack) is written on every build, in every mode. Builds run on
 * one background thread and are swapped in on the main thread. A hot swap keeps everyone as they were
 * (pack-state.yml, written on disable and read on enable, as RaiseAMob Core does), and the last build is
 * read back from export/ on enable, so nothing is rebuilt or sent again needlessly.
 */
public final class PackManager implements PackService, Listener {

    static final String STATE_FILE = "pack-state.yml";
    static final String BUILDS_FILE = "pack-builds.yml";
    static final String EXPORT = "export";
    /** Builds remembered (their SHA-1 and skins), for own/url mode's pack.sha1. */
    private static final int HISTORY = 50;
    private static final int RETRIES = 5;
    private static final Pattern PACK_PATH = Pattern.compile("/pack/([0-9a-f]{40})\\.zip");

    /** Our pack as offered now: its id (from link and SHA-1), link, SHA-1, the skins in it, and whether we know them. */
    record Own(UUID id, URI url, String sha1, Set<String> keys, boolean known) {
    }

    /** A build to make: these skins, for this request (generation), and how often it was retried. */
    private record Request(List<Skin> skins, int generation, int retries, Settings.PackMode mode) {
    }

    private final LivelyNpcs plugin;
    private final SkinService skins;
    private final WebServer web;
    private final PackTracker tracker = new PackTracker();
    private final List<Consumer<Player>> listeners = new CopyOnWriteArrayList<>();
    /** Main thread: the skins each online player was last said to have models of (listeners fire on a change). */
    private final Map<UUID, Set<String>> told = new HashMap<>();
    /** builtin: zips the web server hands out, by SHA-1 (the current one and the one before). */
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    /** Every pack built here, SHA-1 to its skins, oldest first. Guarded by itself. */
    private final LinkedHashMap<String, List<String>> history = new LinkedHashMap<>();
    private final Object buildLock = new Object();
    private final Object buildsFileLock = new Object();

    private ExecutorService builder;
    private PacketListenerCommon packetListener;
    private boolean webHandler;
    private volatile PackBuilder.Pack built;
    private volatile Own current;
    private volatile Set<String> mergedKeys = Set.of();
    private volatile String buildProblem;
    private volatile boolean buildingNow;
    private Settings.PackMode lastMode;
    /** Main thread: the keys of the last rebuild asked for, and its number. */
    private SortedSet<String> requested = new TreeSet<>();
    private int generation;
    /** Guarded by buildLock. */
    private Request pending;
    private boolean building;

    public PackManager(LivelyNpcs plugin, SkinService skins, WebServer web) {
        this.plugin = plugin;
        this.skins = skins;
        this.web = web;
    }

    // ---- life cycle ----

    @Override
    public void start() {
        builder = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LivelyNPCs-pack");
            t.setDaemon(true);
            return t;
        });
        Settings settings = plugin.settings();
        lastMode = settings.packMode;
        loadBuilds();
        loadLastPack();
        if (web.running()) {
            try {
                web.handle("/pack/", this::servePack);
                webHandler = true;
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not serve the NPC pack from the web server: " + e.getMessage());
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            packetListener = PacketEvents.getAPI().getEventManager().registerListener(new Packets(), PacketListenerPriority.MONITOR);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Could not watch resource pack packets (" + e + "): merged mode only sees packs sent after join");
        }
        resume();
        apply(true);
        PackBuilder.Pack pack = built;
        if (pack == null) {
            rebuild(lastKeys());
        } else if (settings.packMode == Settings.PackMode.MERGED) {
            builder.execute(() -> writeMergedQuietly(pack));
        }
    }

    @Override
    public void stop() {
        saveState();
        if (packetListener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            } catch (RuntimeException | LinkageError ignored) {
                // PacketEvents is going too
            }
            packetListener = null;
        }
        if (builder != null) {
            builder.shutdownNow();
            try {
                builder.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            builder = null;
        }
        listeners.clear();
    }

    @Override
    public void reload() {
        Settings settings = plugin.settings();
        PackBuilder.Pack pack = built;
        if (settings.packMode == Settings.PackMode.MERGED && lastMode != Settings.PackMode.MERGED && pack != null && builder != null) {
            builder.execute(() -> writeMergedQuietly(pack));
        }
        lastMode = settings.packMode;
        apply(true);
    }

    // ---- what players have ----

    @Override
    public boolean hasModels(Player player, String skinKey) {
        return player != null && tracker.hasModels(player.getUniqueId(), skinKey);
    }

    @Override
    public void addListener(Consumer<Player> changed) {
        listeners.add(changed);
    }

    /** Tells the listeners about this player if the skins they have models of changed. Main thread. */
    private void tell(Player player) {
        UUID id = player.getUniqueId();
        Set<String> now = tracker.modelKeys(id);
        Set<String> before = told.put(id, now);
        if (now.equals(before == null ? Set.of() : before)) return;
        for (Consumer<Player> listener : listeners) {
            try {
                listener.accept(player);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "A pack listener failed for " + player.getName(), e);
            }
        }
    }

    private void tellEveryone() {
        for (Player player : Bukkit.getOnlinePlayers()) tell(player);
    }

    // ---- the pack offered now ----

    /**
     * Works out the pack to offer from the settings and the last build, offers it to everyone online who
     * does not have it yet (taking an older version away first) and tells the listeners who changed.
     */
    private void apply(boolean log) {
        Settings settings = plugin.settings();
        tracker.policy(settings.packMode == Settings.PackMode.MERGED ? PackTracker.Mode.MERGED : PackTracker.Mode.OWN,
                settings.mergedUrlContains);
        tracker.merged(mergedKeys);
        Own next = own(settings);
        if (next != null) tracker.version(next.id(), next.keys());
        Own before = current;
        current = next;
        for (Player player : Bukkit.getOnlinePlayers()) ensure(player);
        tracker.prune(next == null ? null : next.id());
        tellEveryone();
        if (log && !Objects.equals(before, next)) {
            if (next != null) {
                plugin.getLogger().info("NPC pack: " + next.url() + " (" + next.keys().size() + " skins)"
                        + (next.known() ? "" : "; pack.sha1 is not a pack built here, so its skins are not known: /npc pack"));
            } else if (before != null) {
                plugin.getLogger().info("NPC pack: none is sent now");
            }
        }
    }

    /** The own pack to offer under these settings, or null. */
    private Own own(Settings settings) {
        if (settings.packMode == Settings.PackMode.MERGED) return null;
        PackBuilder.Pack pack = built;
        if (settings.packHost == Settings.PackHost.BUILTIN) {
            if (pack == null || pack.keys().isEmpty() || !webHandler || !web.running()) return null;
            URI url = URI.create(web.baseUrl() + "/pack/" + pack.sha1() + ".zip");
            return new Own(id(url, pack.sha1()), url, pack.sha1(), pack.keys(), true);
        }
        if (settings.packUrl == null || settings.packSha1.isEmpty()) return null;
        List<String> keys = keysOf(settings.packSha1);
        return new Own(id(settings.packUrl, settings.packSha1), settings.packUrl, settings.packSha1,
                keys == null ? Set.of() : Set.copyOf(keys), keys != null);
    }

    /** A pack's id from its link and SHA-1: the same pack always has the same id, a changed one a new one. */
    static UUID id(URI url, String sha1) {
        return UUID.nameUUIDFromBytes(("livelynpcs-pack|" + url + "|" + sha1).getBytes(StandardCharsets.UTF_8));
    }

    /** The skins of a pack built here with this SHA-1, or null if none was. */
    private List<String> keysOf(String sha1) {
        PackBuilder.Pack pack = built;
        if (pack != null && pack.sha1().equals(sha1)) return List.copyOf(pack.keys());
        synchronized (history) {
            return history.get(sha1);
        }
    }

    /** Makes sure the player's game is offered the current pack (and not an older one). Main thread. */
    private void ensure(Player player) {
        Own now = current;
        UUID id = player.getUniqueId();
        switch (tracker.plan(id, now == null ? null : now.id())) {
            case NOTHING -> {
            }
            case SEND -> send(player, now);
            case REPLACE, REMOVE -> {
                UUID old = tracker.offered(id);
                if (old != null) {
                    player.removeResourcePacks(old);
                    tracker.popped(id, old);
                }
                if (now != null) send(player, now);
            }
        }
    }

    private void send(Player player, Own pack) {
        Settings settings = plugin.settings();
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(pack.id(), pack.url(), pack.sha1()))
                .required(settings.packRequired)
                // Beside the server's other packs rather than clearing them; an older version of ours
                // is taken away by its id first (ensure).
                .replace(false);
        if (!settings.packPrompt.isBlank()) request.prompt(MiniMessage.miniMessage().deserialize(settings.packPrompt));
        tracker.offered(player.getUniqueId(), pack.id());
        player.sendResourcePacks(request.build());
    }

    // ---- events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        told.remove(player.getUniqueId());
        ensure(player);
        tell(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        tracker.forget(id);
        told.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PackTracker.Result result = tracker.answer(player.getUniqueId(), event.getID(), event.getStatus());
        if (result == PackTracker.Result.IGNORED) return;
        Status status = event.getStatus();
        if (tracker.ours(event.getID()) && status != Status.ACCEPTED && status != Status.DOWNLOADED) {
            plugin.getLogger().info("NPC pack: " + player.getName() + " " + status);
        }
        tell(player);
    }

    /**
     * Packs offered and taken away (by anyone: the game answers nothing when a pack is taken away), and
     * answers given during the configuration phase (server.properties packs), which fire no Bukkit event.
     * Runs on network threads: the tracker is thread-safe, listeners are told on the main thread.
     */
    private final class Packets implements PacketListener {

        @Override
        public void onPacketSend(PacketSendEvent event) {
            if (event.isCancelled()) return;
            PacketTypeCommon type = event.getPacketType();
            if (type == PacketType.Play.Server.RESOURCE_PACK_SEND) {
                WrapperPlayServerResourcePackSend packet = new WrapperPlayServerResourcePackSend(event);
                pushed(event.getUser(), packet.getPackId(), packet.getUrl());
            } else if (type == PacketType.Configuration.Server.RESOURCE_PACK_SEND) {
                WrapperConfigServerResourcePackSend packet = new WrapperConfigServerResourcePackSend(event);
                pushed(event.getUser(), packet.getPackId(), packet.getUrl());
            } else if (type == PacketType.Play.Server.RESOURCE_PACK_REMOVE) {
                popped(event.getUser(), new WrapperPlayServerResourcePackRemove(event).getPackId());
            } else if (type == PacketType.Configuration.Server.RESOURCE_PACK_REMOVE) {
                popped(event.getUser(), new WrapperConfigServerResourcePackRemove(event).getPackId());
            }
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPacketType() != PacketType.Configuration.Client.RESOURCE_PACK_STATUS) return;
            WrapperConfigClientResourcePackStatus packet = new WrapperConfigClientResourcePackStatus(event);
            UUID player = event.getUser() == null ? null : event.getUser().getUUID();
            if (player == null || packet.getResult() == null) return;
            Status status;
            try {
                status = Status.valueOf(packet.getResult().name());
            } catch (IllegalArgumentException e) {
                return;
            }
            if (tracker.answer(player, packet.getPackId(), status) == PackTracker.Result.CHANGED) later(player, false);
        }

        @Override
        public void onUserDisconnect(UserDisconnectEvent event) {
            UUID player = event.getUser() == null ? null : event.getUser().getUUID();
            if (player == null) return;
            // Left during the configuration phase: no quit event comes, so forget them here (if not online again).
            runLater(() -> {
                if (Bukkit.getPlayer(player) == null) {
                    tracker.forget(player);
                    told.remove(player);
                }
            });
        }

        private void pushed(User user, UUID pack, String url) {
            UUID player = user == null ? null : user.getUUID();
            if (player == null || pack == null) return;
            tracker.pushed(player, pack, url);
            later(player, false);
        }

        private void popped(User user, UUID pack) {
            UUID player = user == null ? null : user.getUUID();
            if (player == null) return;
            boolean ours = tracker.popped(player, pack);
            later(player, ours);
        }

        /** On the main thread: tell listeners about the player, and offer our pack again if someone took it away. */
        private void later(UUID player, boolean offerAgain) {
            runLater(() -> {
                Player online = Bukkit.getPlayer(player);
                if (online == null) return;
                if (offerAgain) ensure(online);
                tell(online);
            });
        }
    }

    private void runLater(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
            // turning off
        }
    }

    // ---- building ----

    @Override
    public void rebuild(Collection<String> skinKeys) {
        SortedSet<String> keys = new TreeSet<>();
        for (String key : skinKeys) if (key != null && Skin.KEY.matcher(key).matches()) keys.add(key);
        requested = keys;
        queue(keys, ++generation, 0);
    }

    private void queue(SortedSet<String> keys, int number, int retries) {
        if (builder == null) return;
        List<Skin> chosen = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String key : keys) {
            Skin skin = skins.get(key);
            if (skin == null) unknown.add(key);
            else chosen.add(skin);
        }
        if (!unknown.isEmpty()) {
            plugin.getLogger().warning("No skin " + String.join(", ", unknown) + " in skins.yml; left out of the NPC pack");
        }
        synchronized (buildLock) {
            pending = new Request(List.copyOf(chosen), number, retries, plugin.settings().packMode);
            if (building) return;
            building = true;
        }
        builder.execute(this::buildLoop);
    }

    /** The build thread: builds the newest request until none is waiting. */
    private void buildLoop() {
        while (true) {
            Request request;
            synchronized (buildLock) {
                request = pending;
                pending = null;
                if (request == null) {
                    building = false;
                    return;
                }
            }
            buildingNow = true;
            try {
                List<PackBuilder.SkinInput> inputs = new ArrayList<>();
                List<String> failed = new ArrayList<>();
                for (Skin skin : request.skins()) {
                    try {
                        inputs.add(new PackBuilder.SkinInput(skin.key(), skin.slim(), SkinImage.read(skins.png(skin))));
                    } catch (Exception e) {
                        failed.add(skin.key() + " (" + e.getMessage() + ")");
                    }
                }
                PackBuilder.Pack pack = PackBuilder.build(inputs);
                Path export = exportDir();
                PackBuilder.writeAtomically(export.resolve(PackBuilder.PACK_ZIP), pack.zip());
                if (request.mode() == Settings.PackMode.MERGED) writeMerged(pack);
                remember(pack);
                buildProblem = failed.isEmpty() ? null
                        : "skins left out because their textures could not be had: " + String.join(", ", failed);
                runLater(() -> applyBuild(pack, request, failed));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                buildProblem = "the last build failed: " + e.getMessage();
                plugin.getLogger().log(Level.WARNING, "Building the NPC pack failed", e);
            } finally {
                buildingNow = false;
            }
        }
    }

    /** Main thread: a finished build becomes the pack. */
    private void applyBuild(PackBuilder.Pack pack, Request request, List<String> failed) {
        if (builder == null) return;
        PackBuilder.Pack before = built;
        built = pack;
        boolean changed = before == null || !before.sha1().equals(pack.sha1());
        served.put(pack.sha1(), pack.zip());
        served.keySet().retainAll(before == null ? Set.of(pack.sha1()) : Set.of(pack.sha1(), before.sha1()));
        if (!failed.isEmpty()) {
            plugin.getLogger().warning("NPC pack: " + buildProblem + (request.retries() < RETRIES ? "; trying again in 2 minutes" : ""));
            if (request.retries() < RETRIES) {
                try {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (generation == request.generation()) queue(requested, generation, request.retries() + 1);
                    }, 20L * 120);
                } catch (IllegalPluginAccessException ignored) {
                    // turning off
                }
            }
        }
        if (changed) {
            plugin.getLogger().info("NPC pack built: " + pack.keys().size() + " skins, SHA-1 " + pack.sha1());
            Settings settings = plugin.settings();
            if (settings.packMode == Settings.PackMode.OWN && settings.packHost == Settings.PackHost.URL
                    && !pack.sha1().equals(settings.packSha1) && !pack.keys().isEmpty()) {
                plugin.getLogger().warning("NPC skins changed: upload plugins/LivelyNPCs/" + EXPORT + "/" + PackBuilder.PACK_ZIP
                        + ", set pack.url to its link and pack.sha1 to " + pack.sha1() + ", then /npc reload. Until then new skins"
                        + " show as player models.");
            }
            if (settings.packMode == Settings.PackMode.MERGED) {
                List<String> missing = new ArrayList<>(pack.keys());
                missing.removeAll(mergedKeys);
                if (!missing.isEmpty()) {
                    plugin.getLogger().info("NPC skins not in the server's pack yet: " + String.join(", ", missing) + ". Merge plugins/"
                            + "LivelyNPCs/" + EXPORT + "/ into it, send it, then run /npc pack merged.");
                }
            }
        }
        apply(changed);
    }

    private Path exportDir() {
        return plugin.getDataFolder().toPath().resolve(EXPORT);
    }

    /** merged mode's export/: assets/ (our namespace replaced, atlases kept with our source) and the assets zip. */
    private void writeMerged(PackBuilder.Pack pack) throws IOException {
        Path export = exportDir();
        PackBuilder.writeAssets(export, pack);
        PackBuilder.writeAtomically(export.resolve(PackBuilder.ASSETS_ZIP), pack.assetsZip());
    }

    private void writeMergedQuietly(PackBuilder.Pack pack) {
        try {
            writeMerged(pack);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write the NPC models to " + EXPORT + "/: " + e.getMessage());
        }
    }

    /** export/lively-npcs-pack.zip from the last run, if it is a pack built here: the pack until the next build. */
    private void loadLastPack() {
        Path file = exportDir().resolve(PackBuilder.PACK_ZIP);
        if (!Files.isRegularFile(file)) return;
        try {
            PackBuilder.Pack pack = PackBuilder.read(Files.readAllBytes(file));
            built = pack;
            served.put(pack.sha1(), pack.zip());
            synchronized (history) {
                history.putIfAbsent(pack.sha1(), List.copyOf(pack.keys()));
            }
        } catch (IOException e) {
            plugin.getLogger().warning(EXPORT + "/" + PackBuilder.PACK_ZIP + " could not be read (" + e.getMessage()
                    + "); the pack is built again");
        }
    }

    /** The skins of the last build (to build again when export/ is gone), else every skin in skins.yml. */
    private Collection<String> lastKeys() {
        synchronized (history) {
            if (!history.isEmpty()) {
                List<String> last = null;
                for (List<String> keys : history.values()) last = keys;
                return last;
            }
        }
        List<String> all = new ArrayList<>();
        for (Skin skin : skins.all()) all.add(skin.key());
        return all;
    }

    // ---- pack-builds.yml: the builds made here and the merged pack's skins ----

    private void remember(PackBuilder.Pack pack) {
        synchronized (history) {
            history.remove(pack.sha1());
            history.put(pack.sha1(), List.copyOf(pack.keys()));
            while (history.size() > HISTORY) history.remove(history.keySet().iterator().next());
        }
        saveBuilds();
    }

    private void saveBuilds() {
        synchronized (buildsFileLock) {
            writeBuilds();
        }
    }

    private void writeBuilds() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Lively NPCs: the packs built on this server (newest last) and the skins in each, so the",
                "pack at pack.url is matched to its skins; and the skins the server's own pack has (merged mode, as",
                "of the last /npc pack merged). Kept by the plugin."));
        List<Map<String, Object>> builds = new ArrayList<>();
        synchronized (history) {
            history.forEach((sha1, keys) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("sha1", sha1);
                entry.put("skins", new ArrayList<>(keys));
                builds.add(entry);
            });
        }
        yaml.set("builds", builds);
        yaml.set("merged", new ArrayList<>(new TreeSet<>(mergedKeys)));
        try {
            PackBuilder.writeAtomically(plugin.getDataFolder().toPath().resolve(BUILDS_FILE),
                    yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write " + BUILDS_FILE + ": " + e.getMessage());
        }
    }

    private void loadBuilds() {
        File file = new File(plugin.getDataFolder(), BUILDS_FILE);
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        synchronized (history) {
            history.clear();
            for (Map<?, ?> entry : yaml.getMapList("builds")) {
                Object sha1 = entry.get("sha1");
                Object keys = entry.get("skins");
                if (!(sha1 instanceof String s) || !s.matches("[0-9a-f]{40}") || !(keys instanceof List<?> list)) continue;
                List<String> valid = new ArrayList<>();
                for (Object k : list) if (k != null && Skin.KEY.matcher(String.valueOf(k)).matches()) valid.add(String.valueOf(k));
                history.put(s, List.copyOf(valid));
            }
        }
        Set<String> merged = new TreeSet<>();
        for (String key : yaml.getStringList("merged")) if (Skin.KEY.matcher(key).matches()) merged.add(key);
        mergedKeys = Set.copyOf(merged);
    }

    @Override
    public void confirmMerged() {
        PackBuilder.Pack pack = built;
        mergedKeys = pack == null ? Set.of() : Set.copyOf(pack.keys());
        saveBuilds();
        plugin.getLogger().info("NPC pack: the server's pack now counts as having " + mergedKeys.size() + " skins");
        apply(false);
    }

    // ---- the web server (own/builtin) ----

    private void servePack(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            WebServer.bytes(exchange, 405, "text/plain; charset=utf-8", "GET only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Matcher m = PACK_PATH.matcher(exchange.getRequestURI().getPath());
        byte[] zip = m.matches() ? served.get(m.group(1)) : null;
        if (zip == null) {
            WebServer.bytes(exchange, 404, "text/plain; charset=utf-8", "No such pack".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // A pack's link names its SHA-1, so its bytes never change: any cache may keep it.
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + PackBuilder.PACK_ZIP + "\"");
        WebServer.bytes(exchange, 200, "application/zip", zip);
    }

    // ---- hot swap: pack-state.yml ----

    /** On disable: who is online and what their games have, so the next copy (a hot swap) carries on. */
    private void saveState() {
        File file = new File(plugin.getDataFolder(), STATE_FILE);
        Map<UUID, Integer> sessions = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) sessions.put(player.getUniqueId(), player.getEntityId());
        if (sessions.isEmpty()) {
            if (file.isFile() && !file.delete()) plugin.getLogger().warning("Could not delete " + file);
            return;
        }
        YamlConfiguration state = new YamlConfiguration();
        tracker.save(state, run(), sessions);
        try {
            state.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write " + STATE_FILE + " (" + e.getMessage()
                    + "); after the swap, everyone online is offered the NPC pack again");
        }
    }

    /** Players already online when starting (a hot swap): what the previous copy knew of them is restored. */
    private void resume() {
        File file = new File(plugin.getDataFolder(), STATE_FILE);
        YamlConfiguration saved = file.isFile() ? YamlConfiguration.loadConfiguration(file) : null;
        if (saved != null && !file.delete()) plugin.getLogger().warning("Could not delete " + file);
        long run = run();
        int kept = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (tracker.resume(saved, run, player.getUniqueId(), player.getEntityId())) kept++;
        }
        if (kept > 0) plugin.getLogger().info("NPC pack: picked up " + kept + " player(s) as they were");
    }

    /** This run of the server: entity ids, the session marks in pack-state.yml, start over with each run. */
    private static long run() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    // ---- /npc pack ----

    @Override
    public List<String> status() {
        Settings settings = plugin.settings();
        PackBuilder.Pack pack = built;
        Own own = current;
        List<String> lines = new ArrayList<>();
        String builtText = pack == null ? "not built yet"
                : pack.keys().size() + " skin(s), " + Math.max(1, (pack.zip().length + 1023) / 1024) + " KB, SHA-1 " + pack.sha1();
        if (buildingNow) builtText += " (building a new one now)";
        String export = "plugins/LivelyNPCs/" + EXPORT + "/";
        if (settings.packMode == Settings.PackMode.MERGED) {
            lines.add("Pack mode: merged. The server's own pack carries the NPC models.");
            lines.add("Models: " + builtText + ", in " + export + " (assets/ and " + PackBuilder.ASSETS_ZIP + ")");
            lines.add("Counts: " + (settings.mergedUrlContains.isEmpty() ? "any server pack a player loads"
                    : "a server pack whose link contains '" + settings.mergedUrlContains + "'"));
            lines.add("In the server's pack (as of the last /npc pack merged): " + mergedKeys.size() + " skin(s)");
            if (pack != null) {
                List<String> missing = new ArrayList<>(pack.keys());
                missing.removeAll(mergedKeys);
                if (!missing.isEmpty()) {
                    lines.add("Not in it yet (shown as player models): " + String.join(", ", missing) + ". Merge " + export
                            + " into the server's pack, send it, then run /npc pack merged.");
                }
            }
        } else if (settings.packHost == Settings.PackHost.BUILTIN) {
            lines.add("Pack mode: own, sent from Lively NPCs' built-in web server.");
            lines.add("Pack: " + builtText);
            if (!webHandler || !web.running()) {
                lines.add("The built-in web server is not running (web.enabled is false, or its port is taken), so no pack is sent."
                        + " Fix web: in config.yml, or upload " + export + PackBuilder.PACK_ZIP + " and use pack.host: url.");
            } else if (own != null) {
                lines.add("Link: " + own.url());
                String host = own.url().getHost() == null ? "" : own.url().getHost().toLowerCase(Locale.ROOT);
                if (host.equals("localhost") || host.startsWith("127.") || host.equals("0.0.0.0")) {
                    lines.add("That link only works on this machine: set web.public-address to the address players use"
                            + " (e.g. play.example.com:" + settings.webPort + "), then /npc reload.");
                }
            } else if (pack != null && pack.keys().isEmpty()) {
                lines.add("No NPC wears a skin of its own, so no pack is needed.");
            }
        } else {
            lines.add("Pack mode: own, from pack.url.");
            lines.add("Built here: " + builtText + " -> " + export + PackBuilder.PACK_ZIP);
            if (settings.packUrl == null || settings.packSha1.isEmpty()) {
                lines.add("No pack is sent: upload " + export + PackBuilder.PACK_ZIP + " where players can download it (GitHub,"
                        + " mc-packs.net), set pack.url to its link" + (pack == null ? "" : " and pack.sha1 to " + pack.sha1())
                        + ", then /npc reload.");
            } else {
                lines.add("Link: " + settings.packUrl + " (SHA-1 " + settings.packSha1 + ")");
                if (pack != null && !pack.sha1().equals(settings.packSha1)) {
                    if (own != null && own.known()) {
                        lines.add("The pack at pack.url is an older build (" + own.keys().size() + " skins): newer skins show as player"
                                + " models. Upload " + export + PackBuilder.PACK_ZIP + " again, set pack.sha1 to " + pack.sha1()
                                + ", then /npc reload.");
                    } else {
                        lines.add("pack.sha1 is not a pack built on this server, so nobody is shown models from it. Upload " + export
                                + PackBuilder.PACK_ZIP + ", set pack.sha1 to " + pack.sha1() + ", then /npc reload.");
                    }
                }
            }
        }
        int online = 0, with = 0, declined = 0, failed = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            online++;
            UUID id = player.getUniqueId();
            if (tracker.hasPack(id)) {
                with++;
                continue;
            }
            Status answer = own == null ? null : tracker.answerAbout(id, own.id());
            if (answer == Status.DECLINED) declined++;
            else if (answer == Status.FAILED_DOWNLOAD || answer == Status.INVALID_URL || answer == Status.FAILED_RELOAD
                    || answer == Status.DISCARDED) failed++;
        }
        String players = "Players: " + online + " online, " + with + " with the pack";
        if (declined > 0) players += ", " + declined + " said no";
        if (failed > 0) players += ", " + failed + " could not load it";
        lines.add(players);
        String problem = buildProblem;
        if (problem != null) lines.add("Note: " + problem);
        return lines;
    }
}
