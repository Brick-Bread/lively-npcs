package dev.livelynpcs.npc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.entity.villager.VillagerData;
import com.github.retrooper.packetevents.protocol.entity.villager.profession.VillagerProfessions;
import com.github.retrooper.packetevents.protocol.entity.villager.type.VillagerTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.Settings;
import dev.livelynpcs.anim.Animation;
import dev.livelynpcs.anim.AnimationService;
import dev.livelynpcs.anim.AnimationStore;
import dev.livelynpcs.pack.PackService;
import dev.livelynpcs.rig.Look;
import dev.livelynpcs.rig.Motion;
import dev.livelynpcs.rig.RigModel;
import dev.livelynpcs.rig.RigPose;
import dev.livelynpcs.skin.Skin;
import dev.livelynpcs.skin.SkinService;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.meta.EntityMeta;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import me.tofaa.entitylib.meta.display.ItemDisplayMeta;
import me.tofaa.entitylib.meta.display.TextDisplayMeta;
import me.tofaa.entitylib.wrapper.WrapperEntity;
import me.tofaa.entitylib.wrapper.WrapperLivingEntity;
import me.tofaa.entitylib.wrapper.WrapperPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The NPC runtime: npcs.yml, drawing every NPC with packets for the players in its world, their looks,
 * animations and clicks. Main thread only (the click listener hops there).
 *
 * <p>A person NPC with a skin is a moving model (RigModel) for players whose game has that skin's
 * models (PackService.hasModels) and a player model wearing the skin for everyone else; a player's look
 * switches on the spot when that changes. A person without a skin is a player model with the default
 * skin, and a mob is that mob for everyone. Every NPC has its plate (MiniMessage lines) and, if it has
 * one, an icon turning above the plate.
 *
 * <p>Every step (every npcs.key-ticks, or every 2 ticks when that is even) the runtime works out, for
 * each player and each NPC in their world: the model's keyframes (Motion: looking, breathing, idle and
 * their own animations); a player model's or mob's head turning to them, its idle swing or nod; a
 * greeting when they walk up within greet-radius (sparkles too, never on joining); and now and then a
 * beckon from the NPC they face most, 6 to 16 blocks away. Idle animations are the same moment for
 * everyone. Nothing exists or ticks in the world, and nothing is sent beyond animate-radius.
 *
 * <p>Safe to hot-swap: on enable it picks up the players already online, on disable it takes every NPC
 * off every screen (and EntityLib lets go of players who quit, which it otherwise would not).
 */
public final class NpcManager implements NpcService {

    /** An editor preview: the draft playing for a player on one NPC. */
    private record Preview(String npc, Animation draft) {
    }

    /** What one online player has been set up with. */
    private static final class Viewer {
        final UUID id;
        String world;
        /** No beckon before this tick (beckon-every after the last one). */
        int nextBeckon;

        Viewer(UUID id, String world) {
            this.id = id;
            this.world = world;
        }
    }

    /** A mob's nod: its head dips this many degrees for this many ticks. */
    private static final float NOD_DEGREES = 20f;
    private static final int NOD_TICKS = 9;
    /** A player model or mob following someone: a turn smaller than this (degrees) is not worth a packet. */
    private static final float TURN = 2f;
    /** Idle swings and nods are shown to players this close (blocks), and never beyond animate-radius. */
    private static final double IDLE_RANGE = 16;
    /** How long an NPC's chunk must have been on a client before the player model's list entry is dropped. */
    private static final int UNLIST_TICKS = 200;
    /** Ticks between two quarter turns of the icons; each turn is smoothed over slightly longer. */
    private static final int SPIN_TICKS = 40;
    /** With nobody to beckon, look again this many ticks later. */
    private static final int BECKON_CHECK = 10;
    private static final float ICON_SCALE = 0.45f, BOB = 0.1f;
    /** Block and sky light 15: plates and icons stay bright in the shade. */
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);
    /** A plate's bottom edge sits PLATE_GAP above the head; a text display grows upwards, LINE a line plus PAD. */
    private static final double PLATE_GAP = 0.3, LINE = 0.25, PAD = 0.025, ICON_GAP = 0.1;
    /** A player model's shown skin layers (slot 16, a byte; 0x7F all) and a villager's data (slot 19) in 26.3. */
    private static final byte SKIN_PARTS = 16, VILLAGER_DATA = 19;
    /** The packet-only team every NPC is in on each player's client: no name tags, no pushing. */
    private static final String TEAM = "livelynpcs";
    /** A skin key or item id that names a resource: anything else would drop the client's connection. */
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final LivelyNpcs plugin;
    private final SkinService skins;
    private final AnimationService animations;
    private final PackService pack;
    /** The NPCs as npcs.yml has them, by id. */
    private final TreeMap<String, NpcDef> defs = new TreeMap<>();
    private final Map<String, Drawn> drawn = new HashMap<>();
    private final Map<String, List<Drawn>> worlds = new HashMap<>();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    /** Which NPC a clicked entity id belongs to; read on network threads. */
    private final Map<Integer, String> targets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Listener events = new Listener() { };
    private final Random random = new Random();
    private PacketListenerCommon clicks;
    private BukkitTask stepper, spinner;
    private int ticks, stepTicks = 2, models, spinStep, lastWarned = Integer.MIN_VALUE / 2;
    /** The skin keys the pack was last told about (null: not yet). */
    private List<String> packKeys;

    public NpcManager(LivelyNpcs plugin, SkinService skins, AnimationService animations, PackService pack) {
        this.plugin = plugin;
        this.skins = skins;
        this.animations = animations;
        this.pack = pack;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Reads npcs.yml, draws the NPCs for everyone online and starts the steps; called once on enable. */
    public void start() {
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvent(PlayerJoinEvent.class, events, EventPriority.MONITOR, (l, e) -> {
            if (e instanceof PlayerJoinEvent join) join(join.getPlayer());
        }, plugin);
        pm.registerEvent(PlayerChangedWorldEvent.class, events, EventPriority.MONITOR, (l, e) -> {
            if (e instanceof PlayerChangedWorldEvent change) moved(change.getPlayer());
        }, plugin);
        pm.registerEvent(PlayerQuitEvent.class, events, EventPriority.MONITOR, (l, e) -> {
            if (e instanceof PlayerQuitEvent quit) forget(quit.getPlayer().getUniqueId());
        }, plugin);
        clicks = PacketEvents.getAPI().getEventManager().registerListener(new Clicks(), PacketListenerPriority.NORMAL);
        pack.addListener(this::packChanged);
        load();
        stepTicks = stepTicks(plugin.settings().keyTicks);
        drawAll();
        for (Player player : Bukkit.getOnlinePlayers()) join(player);
        startTasks();
        tellPack(true);
    }

    /** Takes every NPC off every screen and stops; called on disable. */
    public void stop() {
        stopTasks();
        HandlerList.unregisterAll(events);
        if (clicks != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(clicks);
            clicks = null;
        }
        try {
            undrawAll();
            for (Viewer v : viewers.values()) {
                Player player = Bukkit.getPlayer(v.id);
                if (player != null) send(player, new WrapperPlayServerTeams(TEAM, WrapperPlayServerTeams.TeamMode.REMOVE,
                        (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.<String>of()));
            }
        } catch (RuntimeException e) {
            // Shutting down with players leaving: their screens go anyway, and disabling must finish.
            plugin.getLogger().warning("Could not clear every NPC from every screen: " + e);
        }
        viewers.clear();
        previews.clear();
    }

    @Override
    public void reload() {
        stopTasks();
        undrawAll();
        load();
        stepTicks = stepTicks(plugin.settings().keyTicks);
        drawAll();
        List<String> members = members();
        for (Viewer v : viewers.values()) {
            Player player = Bukkit.getPlayer(v.id);
            if (player == null) continue;
            v.world = player.getWorld().getName();
            sendTeam(player, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, members);
            for (Drawn d : here(v.world)) show(player, d);
        }
        startTasks();
        tellPack(true);
        changed();
    }

    /** Ticks between two steps: every 2 ticks when the keyframe gap is even, else every tick (steps must land on keyframes). */
    static int stepTicks(int keyTicks) {
        return keyTicks % 2 == 0 ? 2 : 1;
    }

    private void startTasks() {
        stepper = Bukkit.getScheduler().runTaskTimer(plugin, this::step, stepTicks, stepTicks);
        spinner = Bukkit.getScheduler().runTaskTimer(plugin, this::spinIcons, SPIN_TICKS, SPIN_TICKS);
    }

    private void stopTasks() {
        if (stepper != null) stepper.cancel();
        if (spinner != null) spinner.cancel();
        stepper = spinner = null;
    }

    // ------------------------------------------------------------------ NpcService

    @Override
    public Collection<NpcDef> all() {
        return Collections.unmodifiableList(new ArrayList<>(defs.values()));
    }

    @Override
    public NpcDef get(String id) {
        return id == null ? null : defs.get(id);
    }

    @Override
    public void put(NpcDef npc) throws IOException {
        validate(npc);
        TreeMap<String, NpcDef> next = new TreeMap<>(defs);
        next.put(npc.id(), npc);
        save(next.values());
        defs.put(npc.id(), npc);
        undraw(npc.id());
        Drawn d = draw(npc);
        if (d != null) {
            for (Viewer v : viewers.values()) {
                Player player = Bukkit.getPlayer(v.id);
                if (player != null) sendTeam(player, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, List.of(d.member));
            }
            showToWorld(d);
        }
        tellPack(false);
        changed();
    }

    @Override
    public boolean remove(String id) throws IOException {
        if (id == null || !defs.containsKey(id)) return false;
        TreeMap<String, NpcDef> next = new TreeMap<>(defs);
        next.remove(id);
        save(next.values());
        defs.remove(id);
        undraw(id);
        tellPack(false);
        changed();
        return true;
    }

    @Override
    public boolean play(String npcId, String animationId, Player player) {
        NpcDef def = get(npcId);
        Animation a = animationId == null ? null : animations.get(animationId);
        if (def == null || a == null || !def.person()) return false;
        Drawn d = drawn.get(npcId);
        if (d == null) return true;
        if (player == null) {
            if (d.rig != null) d.rig.motion().share(a, Motion.Kind.EVERYONE, ticks);
            for (Map.Entry<UUID, Drawn.Seen> e : d.seen.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && !e.getValue().rigged && within(p, d, idleRange())) swing(p, d);
            }
            return true;
        }
        Drawn.Seen s = d.seen.get(player.getUniqueId());
        if (s == null) return true;
        if (s.rigged) s.watch.request(a, Motion.Kind.COMMAND, ticks);
        else swing(player, d);
        return true;
    }

    /**
     * Stops what /npc play started on this NPC: for one player (their command animation and any editor
     * preview), or the one playing for everyone when player is null. False if there is no such NPC.
     */
    public boolean stopPlaying(String npcId, Player player) {
        NpcDef def = get(npcId);
        if (def == null) return false;
        Drawn d = drawn.get(npcId);
        if (d == null || d.rig == null) return true;
        if (player == null) {
            d.rig.motion().stopShared(Motion.Kind.EVERYONE);
        } else {
            Drawn.Seen s = d.seen.get(player.getUniqueId());
            if (s != null && s.watch != null) {
                s.watch.stop(Motion.Kind.COMMAND);
                s.watch.stop(Motion.Kind.PREVIEW);
            }
            Preview p = previews.get(player.getUniqueId());
            if (p != null && p.npc.equals(npcId)) previews.remove(player.getUniqueId());
        }
        return true;
    }

    @Override
    public boolean preview(String npcId, Animation draft, Player player) {
        NpcDef def = get(npcId);
        if (def == null || draft == null || player == null || !def.person()) return false;
        stopPreview(player);
        Drawn d = drawn.get(npcId);
        Drawn.Seen s = d == null ? null : d.seen.get(player.getUniqueId());
        // Only a player who sees the moving model (in its world, with its skin's models loaded) can watch it.
        if (s == null || !s.rigged) return false;
        previews.put(player.getUniqueId(), new Preview(npcId, draft));
        return s.watch.request(draft, Motion.Kind.PREVIEW, ticks);
    }

    @Override
    public void stopPreview(Player player) {
        if (player == null) return;
        Preview p = previews.remove(player.getUniqueId());
        if (p == null) return;
        Drawn d = drawn.get(p.npc);
        Drawn.Seen s = d == null ? null : d.seen.get(player.getUniqueId());
        if (s != null && s.watch != null) s.watch.stop(Motion.Kind.PREVIEW);
    }

    @Override
    public void addListener(Runnable onChange) {
        listeners.add(onChange);
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("An NPC listener failed: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ npcs.yml

    private Path file() {
        return plugin.getDataFolder().toPath().resolve("npcs.yml");
    }

    private void load() {
        defs.clear();
        Path file = file();
        String text;
        try {
            if (!Files.exists(file)) {
                save(List.of());
                return;
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("npcs.yml could not be read (" + e.getMessage() + "), so there are no NPCs until /npc reload");
            return;
        }
        NpcFile.Loaded loaded = NpcFile.read(text);
        for (String problem : loaded.problems()) plugin.getLogger().warning("npcs.yml " + problem);
        for (NpcDef npc : loaded.npcs()) {
            defs.put(npc.id(), npc);
            for (String problem : problems(npc, false)) plugin.getLogger().warning("npcs.yml npcs." + npc.id() + "." + problem);
        }
    }

    private void save(Collection<NpcDef> all) throws IOException {
        Path file = file();
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling("npcs.yml.tmp");
        Files.writeString(temp, NpcFile.write(all), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** put's checks: the first problem, as a message for people. */
    private void validate(NpcDef npc) {
        if (Bukkit.getWorld(npc.world()) == null) throw new IllegalArgumentException("There is no world called '" + npc.world() + "' on this server.");
        List<String> problems = problems(npc, true);
        if (!problems.isEmpty()) {
            String p = problems.getFirst();
            throw new IllegalArgumentException(p.substring(p.indexOf(": ") + 2));
        }
    }

    /**
     * What is wrong with an NPC, each "key: why" (why is for people). strict: for put, where every
     * reference must exist; otherwise (reading npcs.yml) only what makes it draw differently is listed.
     */
    private List<String> problems(NpcDef npc, boolean strict) {
        List<String> out = new ArrayList<>();
        if (npc.person()) {
            if (!npc.skin().isEmpty() && skins.get(npc.skin()) == null) {
                out.add("skin: there is no skin '" + npc.skin() + "' in skins.yml" + (strict
                        ? ": give it one with /npc skin " + npc.id() + " <player name>" : ", so it wears the default skin"));
            }
        } else {
            String mob = mobProblem(npc.entity());
            if (mob != null) out.add("entity: " + mob + (strict ? "" : ", so it is not drawn"));
            if (npc.entity().equals("villager")) {
                try {
                    Mobs.villager(npc.variant());
                } catch (IllegalArgumentException e) {
                    out.add("variant: " + e.getMessage() + (strict ? "" : ", using a plains villager"));
                }
            }
        }
        String icon = itemProblem(npc.icon());
        if (icon != null) out.add("icon: " + icon + (strict ? "" : ", so it has no icon"));
        String held = itemProblem(npc.held());
        if (held != null) out.add("held: " + held + (strict ? "" : ", so it holds nothing"));
        for (String line : npc.click()) {
            try {
                ClickAction.parse(line);
            } catch (IllegalArgumentException e) {
                out.add("click: " + e.getMessage() + (strict ? "" : ", so that one is skipped"));
            }
        }
        if (npc.idle().contains(AnimationStore.NONE) && npc.idle().size() > 1) {
            out.add("idle: [none] means no idle animations, so it cannot go with others");
        }
        for (String id : npc.idle()) {
            if (!id.equals(AnimationStore.NONE) && animations.get(id) == null) out.add("idle: there is no animation called '" + id + "'");
        }
        for (String[] a : new String[][] {{"greet", npc.greet()}, {"clicked", npc.clicked()}, {"beckon", npc.beckon()}}) {
            if (!a[1].isEmpty() && animations.get(a[1]) == null) out.add(a[0] + ": there is no animation called '" + a[1] + "'");
        }
        return out;
    }

    /** Why an entity name is not a mob this can draw, or null when it is. */
    static String mobProblem(String entity) {
        if (entity.equals("player")) return null;
        org.bukkit.entity.EntityType type = NAME.matcher(entity).matches() ? Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(entity)) : null;
        if (type == null || !type.isAlive() || type == org.bukkit.entity.EntityType.PLAYER) {
            return "'" + entity + "' is not a mob (player, villager, copper_golem, zombie, ...)";
        }
        if (EntityTypes.getByName("minecraft:" + entity) == null) return "'" + entity + "' is a mob Lively NPCs cannot draw yet";
        return null;
    }

    /** Why an item id is not an item, or null when it is (or is empty: none). */
    static String itemProblem(String item) {
        if (item.isEmpty()) return null;
        Material m = RESOURCE.matcher(item).matches() ? Material.matchMaterial(item) : null;
        if (m == null || !m.isItem() || m.isAir()) return "'" + item + "' is not an item";
        if (peItem(item) == null) return "'" + item + "' is an item Lively NPCs cannot draw yet";
        return null;
    }

    /** The PacketEvents item stack for an item id, or null. */
    static ItemStack peItem(String item) {
        if (item == null || item.isEmpty() || !RESOURCE.matcher(item).matches()) return null;
        ItemType type = ItemTypes.getByName("minecraft:" + item);
        return type == null ? null : ItemStack.builder().type(type).amount(1).build();
    }

    // ------------------------------------------------------------------ drawing

    private void drawAll() {
        for (NpcDef npc : defs.values()) draw(npc);
    }

    /** Draws one NPC (nobody sees it yet); null, logged, when it cannot be drawn. */
    private Drawn draw(NpcDef npc) {
        Settings s = plugin.settings();
        Skin skin = npc.person() && !npc.skin().isEmpty() ? skins.get(npc.skin()) : null;
        WrapperLivingEntity body;
        String member;
        double[] size = Mobs.size(npc.entity());
        if (npc.person()) {
            WrapperPlayer player = playerModel(skin);
            ItemStack held = peItem(npc.held());
            if (held != null) player.getEquipment().setOffhand(held);
            body = player;
            member = player.getUsername();
        } else {
            EntityType type = mobProblem(npc.entity()) == null ? EntityTypes.getByName("minecraft:" + npc.entity()) : null;
            if (type == null) return null;
            body = new WrapperLivingEntity(type);
            if (npc.entity().equals("villager")) villager(body, npc.variant());
            member = body.getUuid().toString();
        }
        EntityMeta meta = body.getEntityMeta();
        meta.setSilent(true);
        meta.setHasNoGravity(true);
        body.spawn(new Location(npc.x(), npc.y(), npc.z(), npc.yaw(), 0f));
        body.rotateHead(npc.yaw(), 0f);
        targets.put(body.getEntityId(), npc.id());

        RigModel rig = null;
        if (skin != null && RESOURCE.matcher(skin.key()).matches()) {
            rig = new RigModel(skin.key(), peItem(npc.held()), npc.x(), npc.y(), npc.z(), npc.yaw(), s.scale, skin.slim(), npc.look(),
                    s.breathTicks, s.keyTicks, stepTicks, random);
            targets.put(rig.hitboxId(), npc.id());
        }
        double height = rig == null ? size[0] : Math.max(size[0], rig.height());
        double eyeY = npc.y() + (rig == null ? size[1] : RigPose.EYES * s.scale / 16);
        WrapperEntity plate = plate(npc, npc.y() + height + PLATE_GAP);
        int lines = npc.plate().size();
        double iconY = plate == null ? npc.y() + height + ICON_GAP + ICON_SCALE / 2
                : npc.y() + height + PLATE_GAP + lines * LINE + PAD + ICON_GAP + ICON_SCALE / 2;
        WrapperEntity icon = icon(npc, iconY);

        Drawn d = new Drawn(npc, skin, body, member, rig, plate, icon, eyeY);
        d.nextIdle = ticks + (int) (s.idleTicks * random.nextDouble());
        drawn.put(npc.id(), d);
        worlds.computeIfAbsent(npc.world(), w -> new ArrayList<>()).add(d);
        return d;
    }

    /**
     * A player model wearing skin (the default skin when null). It stays out of the tab list (listed
     * false), but its player-info entry, which carries the skin, has to reach the client before it
     * spawns; EntityLib sends it first to every new viewer. The name is unique, at most 16 characters,
     * and has a '-', which no real name has; the UUID is version 2, which no real player has.
     */
    private WrapperPlayer playerModel(Skin skin) {
        List<TextureProperty> textures = new ArrayList<>();
        if (skin != null) textures.add(new TextureProperty("textures", skin.value(), skin.signature()));
        UUID random = UUID.randomUUID();
        UUID id = new UUID((random.getMostSignificantBits() & ~0xF000L) | 0x2000L, random.getLeastSignificantBits());
        WrapperPlayer body = new WrapperPlayer(new UserProfile(id, "lnpc-" + ++models, textures),
                EntityLib.getPlatform().getEntityIdProvider().provide(id, EntityTypes.PLAYER));
        body.setInTablist(false);
        body.getEntityMeta().setIndex(SKIN_PARTS, EntityDataTypes.BYTE, (byte) 0x7F);
        return body;
    }

    private static void villager(WrapperLivingEntity body, String variant) {
        String[] v;
        try {
            v = Mobs.villager(variant);
        } catch (IllegalArgumentException e) {
            v = new String[] {"plains", "none"};
        }
        var type = VillagerTypes.getByName("minecraft:" + v[0]);
        var job = VillagerProfessions.getByName("minecraft:" + v[1]);
        body.getEntityMeta().setIndex(VILLAGER_DATA, EntityDataTypes.VILLAGER_DATA,
                new VillagerData(type == null ? VillagerTypes.PLAINS : type, job == null ? VillagerProfessions.NONE : job, 5));
    }

    /** The plate (its lines, MiniMessage with {npc} as the id), its bottom edge at y; null without lines. */
    private static WrapperEntity plate(NpcDef npc, double y) {
        if (npc.plate().isEmpty()) return null;
        Component text = Component.empty();
        for (int i = 0; i < npc.plate().size(); i++) {
            if (i > 0) text = text.append(Component.newline());
            String line = npc.plate().get(i).replace("{npc}", npc.id());
            Component c;
            try {
                c = MM.deserialize(line);
            } catch (RuntimeException e) {
                c = Component.text(line);              // a line MiniMessage cannot read is shown as it is
            }
            text = text.append(c);
        }
        WrapperEntity plate = new WrapperEntity(EntityTypes.TEXT_DISPLAY);
        TextDisplayMeta tm = (TextDisplayMeta) plate.getEntityMeta();
        tm.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
        tm.setShadow(true);
        tm.setBackgroundColor(0);
        tm.setBrightnessOverride(FULL_BRIGHT);
        tm.setText(text);
        plate.spawn(new Location(npc.x(), y, npc.z(), 0f, 0f));
        return plate;
    }

    /** The small item turning over the plate, centred at y; null with no icon or an unknown item. */
    private static WrapperEntity icon(NpcDef npc, double y) {
        ItemStack item = peItem(npc.icon());
        if (item == null) return null;
        WrapperEntity icon = new WrapperEntity(EntityTypes.ITEM_DISPLAY);
        ItemDisplayMeta im = (ItemDisplayMeta) icon.getEntityMeta();
        im.setItem(item);
        im.setDisplayType(ItemDisplayMeta.DisplayType.NONE);
        im.setScale(new Vector3f(ICON_SCALE, ICON_SCALE, ICON_SCALE));
        im.setBrightnessOverride(FULL_BRIGHT);
        icon.spawn(new Location(npc.x(), y, npc.z(), 0f, 0f));
        return icon;
    }

    /** Takes an NPC off every screen and lets go of its entities. */
    private void undraw(String id) {
        Drawn d = drawn.remove(id);
        if (d == null) return;
        List<Drawn> list = worlds.get(d.def.world());
        if (list != null) {
            list.remove(d);
            if (list.isEmpty()) worlds.remove(d.def.world());
        }
        for (UUID viewer : List.copyOf(d.seen.keySet())) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) hide(player, List.of(d));
            else d.seen.remove(viewer);
        }
        // Its team entry stays on clients: harmless, and the client throws (disconnecting the player) when
        // told to remove an entry it does not have, so entries are only ever added.
        release(d);
    }

    private void undrawAll() {
        for (Viewer v : viewers.values()) {
            Player player = Bukkit.getPlayer(v.id);
            if (player != null) hide(player, here(v.world));
        }
        for (Drawn d : drawn.values()) release(d);
        drawn.clear();
        worlds.clear();
    }

    /** EntityLib lets go of the NPC's entities (their screens were already cleared). */
    private void release(Drawn d) {
        for (WrapperEntity e : d.all()) {
            for (UUID viewer : List.copyOf(e.getViewers())) e.removeViewerSilently(viewer);
            targets.remove(e.getEntityId());
            e.remove();
        }
    }

    private List<Drawn> here(String world) {
        List<Drawn> list = worlds.get(world);
        return list == null ? List.of() : List.copyOf(list);
    }

    private List<String> members() {
        List<String> out = new ArrayList<>();
        for (Drawn d : drawn.values()) out.add(d.member);
        return out;
    }

    // ------------------------------------------------------------------ viewers

    private void join(Player player) {
        UUID id = player.getUniqueId();
        if (viewers.containsKey(id)) return;
        Viewer v = new Viewer(id, player.getWorld().getName());
        viewers.put(id, v);
        // The team first, so a player model's name tag never shows as it spawns.
        sendTeam(player, WrapperPlayServerTeams.TeamMode.CREATE, members());
        for (Drawn d : here(v.world)) show(player, d);
    }

    private void moved(Player player) {
        Viewer v = viewers.get(player.getUniqueId());
        if (v == null) {
            join(player);
            return;
        }
        String world = player.getWorld().getName();
        if (world.equals(v.world)) return;
        hide(player, here(v.world));
        v.world = world;
        for (Drawn d : here(world)) show(player, d);
    }

    /** A player quit: their client is gone, so only the bookkeeping goes (EntityLib would keep them otherwise). */
    private void forget(UUID id) {
        viewers.remove(id);
        previews.remove(id);
        lastClick.remove(id);
        for (Drawn d : drawn.values()) {
            d.seen.remove(id);
            for (WrapperEntity e : d.all()) e.removeViewerSilently(id);
        }
    }

    private void showToWorld(Drawn d) {
        for (Viewer v : viewers.values()) {
            Player player = Bukkit.getPlayer(v.id);
            if (player != null && v.world.equals(d.def.world())) show(player, d);
        }
    }

    /** Spawns the NPC for this player: its model if their game has the skin's models, else its body. */
    private void show(Player player, Drawn d) {
        UUID id = player.getUniqueId();
        if (d.seen.containsKey(id)) return;
        Drawn.Seen s = new Drawn.Seen();
        s.rigged = d.rig != null && pack.hasModels(player, d.def.skin());
        s.yaw = d.def.yaw();
        s.listed = d.person() && !s.rigged;
        if (s.rigged) watch(player, d, s);
        d.seen.put(id, s);
        for (WrapperEntity e : d.seenBy(s.rigged)) e.addViewer(id);
    }

    /** A new model watch for this player, with their looping editor preview back on it if they have one. */
    private void watch(Player player, Drawn d, Drawn.Seen s) {
        s.watch = d.rig.motion().watch();
        Preview p = previews.get(player.getUniqueId());
        if (p != null && p.npc.equals(d.def.id()) && p.draft.loop()) s.watch.request(p.draft, Motion.Kind.PREVIEW, ticks);
    }

    /** Takes these NPCs off this player's screen (one packet), with any player-list entries still there. */
    private void hide(Player player, List<Drawn> list) {
        UUID id = player.getUniqueId();
        List<Integer> gone = new ArrayList<>();
        List<UUID> listed = new ArrayList<>();
        for (Drawn d : list) {
            Drawn.Seen s = d.seen.remove(id);
            if (s == null) continue;
            for (WrapperEntity e : d.seenBy(s.rigged)) gone.add(e.getEntityId());
            if (s.listed) listed.add(d.body.getUuid());
            for (WrapperEntity e : d.all()) e.removeViewerSilently(id);
        }
        if (!gone.isEmpty()) send(player, new WrapperPlayServerDestroyEntities(gone.stream().mapToInt(Integer::intValue).toArray()));
        if (!listed.isEmpty()) send(player, new WrapperPlayServerPlayerInfoRemove(listed));
    }

    /**
     * What PackService.hasModels says for a player may have changed: every NPC with a model whose answer
     * changed switches look for them on the spot (the old look off their screen, the new one at rest).
     */
    private void packChanged(Player player) {
        Viewer v = viewers.get(player.getUniqueId());
        if (v == null) return;
        UUID id = player.getUniqueId();
        List<Integer> gone = new ArrayList<>();
        List<UUID> listed = new ArrayList<>();
        List<Drawn> switched = new ArrayList<>();
        for (Drawn d : here(v.world)) {
            Drawn.Seen s = d.seen.get(id);
            if (s == null || d.rig == null) continue;
            boolean rigged = pack.hasModels(player, d.def.skin());
            if (rigged == s.rigged) continue;
            for (WrapperEntity e : rigged ? List.of((WrapperEntity) d.body) : d.rig.entities()) {
                gone.add(e.getEntityId());
                e.removeViewerSilently(id);
            }
            if (s.listed) listed.add(d.body.getUuid());
            s.listed = false;
            s.rigged = rigged;
            switched.add(d);
        }
        if (switched.isEmpty()) return;
        send(player, new WrapperPlayServerDestroyEntities(gone.stream().mapToInt(Integer::intValue).toArray()));
        if (!listed.isEmpty()) send(player, new WrapperPlayServerPlayerInfoRemove(listed));
        for (Drawn d : switched) {
            Drawn.Seen s = d.seen.get(id);
            if (s.rigged) {
                watch(player, d, s);
                for (WrapperEntity e : d.rig.entities()) e.addViewer(id);
            } else {
                // The player model spawns facing its own way, its entry listed until its skin is on screen.
                s.watch = null;
                s.listed = true;
                s.sent = 0;
                s.yaw = d.def.yaw();
                s.pitch = 0f;
                d.body.addViewer(id);
            }
        }
    }

    private void sendTeam(Player player, WrapperPlayServerTeams.TeamMode mode, List<String> entries) {
        if (mode != WrapperPlayServerTeams.TeamMode.CREATE && entries.isEmpty()) return;
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = mode == WrapperPlayServerTeams.TeamMode.CREATE
                ? new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), Component.empty(), Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER, WrapperPlayServerTeams.CollisionRule.NEVER, NamedTextColor.WHITE,
                WrapperPlayServerTeams.OptionData.NONE) : null;
        send(player, new WrapperPlayServerTeams(TEAM, mode, info, entries));
    }

    // ------------------------------------------------------------------ the step

    /**
     * Every step: each NPC's shared life (its weight, idle animations) first, the same moment for
     * everyone; then, for each player, every NPC in their world: beckons, greetings, the model's
     * keyframes, a player model's or mob's turn, swing and nod.
     */
    private void step() {
        if (viewers.isEmpty() || drawn.isEmpty()) return;
        ticks += stepTicks;
        Settings s = plugin.settings();
        for (Drawn d : drawn.values()) {
            if (d.rig != null) d.rig.motion().live(ticks);
            idle(d, s);
        }
        for (Viewer v : viewers.values()) {
            List<Drawn> list = worlds.get(v.world);
            if (list == null || list.isEmpty()) continue;
            Player player = Bukkit.getPlayer(v.id);
            if (player == null) continue;
            try {
                stepFor(player, v, list, s);
            } catch (RuntimeException failed) {
                // One player's packet going wrong must not stop every NPC for everyone; say so once a minute.
                if (ticks - lastWarned >= 1200) {
                    lastWarned = ticks;
                    plugin.getLogger().warning("NPC step for " + player.getName() + " failed: " + failed);
                }
            }
        }
    }

    /** Now and then each NPC plays one of its idle animations (never the same twice in a row), swings or nods. */
    private void idle(Drawn d, Settings s) {
        if (s.idleTicks <= 0 || ticks < d.nextIdle) return;
        d.nextIdle = ticks + (int) (s.idleTicks * random.nextDouble(0.6, 1.4));
        List<String> mix = d.def.idle().isEmpty() ? AnimationStore.DEFAULT_IDLE : d.def.idle();
        if (mix.contains(AnimationStore.NONE)) return;
        if (!d.person()) {
            d.nodUntil = ticks + NOD_TICKS;
            return;
        }
        d.swingAt = ticks;
        if (d.rig == null || d.rig.motion().sharing(ticks) != null) return;
        List<Animation> choices = new ArrayList<>();
        for (String id : mix) {
            Animation a = animations.get(id);
            if (a != null && (!id.equals(d.lastIdle) || mix.size() == 1)) choices.add(a);
        }
        if (choices.isEmpty()) return;
        Animation a = choices.get(random.nextInt(choices.size()));
        d.rig.motion().share(a, Motion.Kind.IDLE, ticks);
        d.lastIdle = a.id();
        d.nextIdle = Math.max(d.nextIdle, ticks + a.length() + s.idleTicks / 4);
    }

    private void stepFor(Player player, Viewer v, List<Drawn> list, Settings s) {
        org.bukkit.Location eyes = player.getEyeLocation();
        double ex = eyes.getX(), ey = eyes.getY(), ez = eyes.getZ();
        if (ticks >= v.nextBeckon) beckon(v, list, eyes, s);
        User user = null;
        boolean wrote = false;
        double greet = s.greetRadius, idle = Math.min(IDLE_RANGE, s.animateRadius);
        try {
            for (Drawn d : list) {
                Drawn.Seen seen = d.seen.get(v.id);
                if (seen == null) continue;
                double dx = ex - d.def.x(), dy = ey - d.eyeY, dz = ez - d.def.z();
                double far = dx * dx + dy * dy + dz * dz;
                boolean near = greet > 0 && far <= greet * greet && far <= s.animateRadius * s.animateRadius;
                boolean walkedUp = near && !seen.near && seen.known;
                seen.near = near;
                seen.known = true;
                if (walkedUp) greet(player, d, seen, s);
                if (seen.rigged) {
                    if (user == null) user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                    if (user != null) wrote |= d.rig.step(user, seen.watch, ticks, ex, ey, ez, s.lookRadius, s.animateRadius);
                    continue;
                }
                if (seen.listed) unlist(player, d, seen);
                turn(player, d, seen, dx, dy, dz, far, s, idle);
                if (d.person() && d.swingAt == ticks && far <= idle * idle) swing(player, d);
            }
        } finally {
            // The models' keyframes were only written; one flush sends them all.
            if (wrote) user.flushPackets();
        }
    }

    /** A player just walked up: sparkles (npcs.sparkles) and the greeting, unless the NPC greets nobody. */
    private void greet(Player player, Drawn d, Drawn.Seen seen, Settings s) {
        if (d.def.greet().isEmpty()) return;
        if (s.sparkles) sparkle(player, d);
        if (seen.rigged) {
            Animation a = animations.get(d.def.greet());
            if (a != null) seen.watch.request(a, Motion.Kind.GREET, ticks);
        } else if (d.person()) {
            swing(player, d);
        } else {
            seen.nodUntil = ticks + NOD_TICKS;
        }
    }

    /**
     * At most one beckon per player every beckon-every: of the NPCs that can beckon them now (they see
     * its model, it has a beckon animation, it is not busy with them, it has not beckoned them for 30
     * seconds), the one they face most between beckon-from and beckon-to blocks away.
     */
    private void beckon(Viewer v, List<Drawn> list, org.bukkit.Location eyes, Settings s) {
        v.nextBeckon = ticks + BECKON_CHECK;
        double to = Math.min(s.beckonTo, s.animateRadius);
        if (s.beckonFrom <= 0 || to <= s.beckonFrom) return;
        List<Drawn> candidates = new ArrayList<>();
        List<Beckon.Spot> spots = new ArrayList<>();
        for (Drawn d : list) {
            Drawn.Seen seen = d.seen.get(v.id);
            if (seen == null || !seen.rigged || d.def.beckon().isEmpty() || animations.get(d.def.beckon()) == null) continue;
            if (ticks - seen.lastBeckon < Beckon.SAME_NPC_TICKS || seen.watch.playing(ticks) != null) continue;
            candidates.add(d);
            spots.add(new Beckon.Spot(d.def.x(), d.eyeY, d.def.z()));
        }
        if (candidates.isEmpty()) return;
        int pick = Beckon.pick(eyes.getX(), eyes.getY(), eyes.getZ(), eyes.getYaw(), spots, s.beckonFrom, to);
        if (pick < 0) return;
        Drawn d = candidates.get(pick);
        Drawn.Seen seen = d.seen.get(v.id);
        if (seen.watch.request(animations.get(d.def.beckon()), Motion.Kind.BECKON, ticks)) {
            seen.lastBeckon = ticks;
            v.nextBeckon = ticks + s.beckonEveryTicks;
        }
    }

    /**
     * Drops a player model's entry from this player's list once its skin is surely on their screen: the
     * client reads the skin on the model's first tick, which comes once the NPC's chunk is there; after
     * UNLIST_TICKS the entry has done its job, and without it NPC names stay out of chat completion.
     */
    private void unlist(Player player, Drawn d, Drawn.Seen seen) {
        if (!player.isChunkSent(d.chunk)) {
            seen.sent = 0;
            return;
        }
        seen.sent += stepTicks;
        if (seen.sent < UNLIST_TICKS) return;
        seen.listed = false;
        send(player, new WrapperPlayServerPlayerInfoRemove(d.body.getUuid()));
    }

    /**
     * Turns a player model or mob, for this player only, to look at them within look-radius (dipping its
     * head while it nods), and back to its own yaw once they leave. Its body follows the head by itself.
     */
    private void turn(Player player, Drawn d, Drawn.Seen seen, double dx, double dy, double dz, double far, Settings s, double idle) {
        double reach = Math.min(s.lookRadius, s.animateRadius);
        boolean looking = d.def.look() && reach > 0 && far <= reach * reach;
        float yaw = looking ? Look.yaw(dx, dz) : d.def.yaw();
        float pitch = looking ? Look.pitch(dx, dy, dz) : 0f;
        boolean nodding = ticks < seen.nodUntil || (ticks < d.nodUntil && far <= idle * idle);
        if (nodding) pitch = Math.min(90f, pitch + NOD_DEGREES);
        float turned = Look.apart(seen.yaw, yaw), tilted = Math.abs(seen.pitch - pitch);
        // Following someone, a small turn is not worth a packet; going back to rest is always exact.
        if (looking ? turned <= TURN && tilted <= TURN : turned == 0 && tilted == 0) return;
        seen.yaw = yaw;
        seen.pitch = pitch;
        send(player, new WrapperPlayServerEntityRotation(d.body.getEntityId(), yaw, pitch, true));
        send(player, new WrapperPlayServerEntityHeadLook(d.body.getEntityId(), yaw));
    }

    /** An arm swing: 26.3's own swing packet (the old animate packet no longer swings arms), the main hand. */
    private static void swing(Player player, Drawn d) {
        send(player, new WrapperPlayServerSwingAnimation(d.body.getEntityId(), InteractionHand.MAIN_HAND,
                new ItemSwingAnimation(ItemSwingAnimation.Type.WHACK, 6)));
    }

    /** A few happy-villager sparkles round the head: a particle every version has (new ones kick older clients). */
    private static void sparkle(Player player, Drawn d) {
        send(player, new WrapperPlayServerParticle(new Particle<>(ParticleTypes.HAPPY_VILLAGER), false,
                new Vector3d(d.def.x(), d.eyeY + 0.3, d.def.z()), new Vector3f(0.4f, 0.3f, 0.4f), 0f, 8));
    }

    /** Every icon turns a quarter and bobs up or down, smoothed by the client until the next turn. */
    private void spinIcons() {
        if (viewers.isEmpty()) return;
        spinStep++;
        double angle = Math.toRadians(90.0 * spinStep);
        Quaternion4f turn = new Quaternion4f(0f, (float) Math.sin(angle / 2), 0f, (float) Math.cos(angle / 2));
        Vector3f bob = new Vector3f(0f, spinStep % 2 == 0 ? 0f : BOB, 0f);
        for (Drawn d : drawn.values()) {
            if (d.icon == null) continue;
            ItemDisplayMeta meta = (ItemDisplayMeta) d.icon.getEntityMeta();
            meta.setNotifyAboutChanges(false);
            meta.setInterpolationDelay(0);
            meta.setTransformationInterpolationDuration(SPIN_TICKS + 2);
            meta.setLeftRotation(turn);
            meta.setTranslation(bob);
            meta.setNotifyAboutChanges(true);           // sends just the changed values, in one packet
        }
    }

    // ------------------------------------------------------------------ clicks

    /** Clicks (right or left) on any NPC entity: cancelled, and handled on the main thread. */
    private final class Clicks implements PacketListener {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            int entityId;
            if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                entityId = new WrapperPlayClientInteractEntity(event).getEntityId();
            } else if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
                entityId = new WrapperPlayClientAttack(event).getEntityId();
            } else {
                return;
            }
            String npc = targets.get(entityId);
            if (npc == null) return;
            event.setCancelled(true);
            UUID viewer = event.getUser().getUUID();
            if (viewer == null) return;
            long now = System.currentTimeMillis();
            Long last = lastClick.put(viewer, now);
            if (last != null && now - last < 300) return;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(viewer);
                if (player != null && player.isOnline()) clicked(npc, player);
            });
        }
    }

    /** A player clicked this NPC: it plays its clicked animation for them, then the click actions run in order. */
    private void clicked(String npcId, Player player) {
        NpcDef def = defs.get(npcId);
        if (def == null) return;
        Drawn d = drawn.get(npcId);
        Drawn.Seen seen = d == null ? null : d.seen.get(player.getUniqueId());
        if (seen != null && !def.clicked().isEmpty()) {
            if (seen.rigged) {
                Animation a = animations.get(def.clicked());
                if (a != null) seen.watch.request(a, Motion.Kind.CLICKED, ticks);
            } else if (def.person()) {
                swing(player, d);
            } else {
                seen.nodUntil = ticks + NOD_TICKS;
            }
        }
        for (String line : def.click()) {
            ClickAction action;
            try {
                action = ClickAction.parse(line);
            } catch (IllegalArgumentException e) {
                continue;                       // reported when npcs.yml was read
            }
            String text = action.forPlayer(player.getName());
            try {
                switch (action.type()) {
                    case PLAYER -> player.performCommand(text);
                    case CONSOLE -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text);
                    case MESSAGE -> player.sendMessage(MM.deserialize(text));
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("NPC " + npcId + "'s click action '" + line + "' failed for " + player.getName() + ": " + e);
            }
        }
    }

    // ------------------------------------------------------------------ the pack

    /**
     * Tells the pack which skin keys the NPCs wear: always on start and after a reload (the pack builds
     * from exactly this, it does not read npcs.yml), and otherwise only when they changed.
     */
    private void tellPack(boolean always) {
        Set<String> keys = new TreeSet<>();
        for (NpcDef npc : defs.values()) {
            if (npc.person() && !npc.skin().isEmpty() && skins.get(npc.skin()) != null) keys.add(npc.skin());
        }
        List<String> now = List.copyOf(keys);
        if (!always && now.equals(packKeys)) return;
        packKeys = now;
        try {
            pack.rebuild(now);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("The pack could not be rebuilt: " + e);
        }
    }

    // ------------------------------------------------------------------ little helpers

    private double idleRange() {
        return Math.min(IDLE_RANGE, plugin.settings().animateRadius);
    }

    private static boolean within(Player player, Drawn d, double range) {
        org.bukkit.Location eyes = player.getEyeLocation();
        double dx = eyes.getX() - d.def.x(), dy = eyes.getY() - d.eyeY, dz = eyes.getZ() - d.def.z();
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private static void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    /** Whether this NPC is drawn (its world exists and it could be drawn). */
    public boolean drawn(String id) {
        return drawn.containsKey(id);
    }

    /** How this player sees an NPC now, for /npc list: "model", "player model", "mob", or null when they do not. */
    public String lookFor(String id, Player player) {
        Drawn d = drawn.get(id);
        Drawn.Seen s = d == null ? null : d.seen.get(player.getUniqueId());
        if (s == null) return null;
        return s.rigged ? "model" : d.person() ? "player model" : "mob";
    }
}
