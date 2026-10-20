package dev.livelynpcs.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Who has which NPC models, from what their games say. It makes no server calls, so it can be tested:
 * PackManager feeds it the facts (packs offered to a player and their links, the game's answers, packs
 * taken away) and the policy (mode, which skins each of our pack versions holds, which skins the
 * server's pack holds in merged mode), and asks it what a player has and what they need.
 *
 * <p>A player has the models for a skin when a pack their game has SUCCESSFULLY_LOADED (and not lost
 * since) holds that skin: in own mode one of our versions, by its id; in merged mode any other server
 * pack (or only one whose link contains merged-url-contains), which holds the skins confirmed with
 * /npc pack merged. Answers about the same pack repeated are ignored. A pack offered again, taken
 * away (no answer comes for that), or the player quitting forgets it. Thread-safe: answers arrive on the
 * main thread, offers and removals seen on the wire on network threads.
 */
final class PackTracker {

    enum Mode { OWN, MERGED }

    /** What one answer did: nothing new (the same answer again), noted, or changed what is loaded. */
    enum Result { IGNORED, NOTED, CHANGED }

    /** What a player needs so their game is offered the current own pack: nothing, it, it instead of an older one, or the old one taken away. */
    enum Action { NOTHING, SEND, REPLACE, REMOVE }

    /** What we know of one player's game. */
    private static final class Seen {
        /** Packs offered to them (by anyone) and their links, "" when not known. */
        final Map<UUID, String> urls = new HashMap<>();
        /** The last answer about each pack. */
        final Map<UUID, Status> answers = new HashMap<>();
        /** Packs SUCCESSFULLY_LOADED and not failed, taken away or offered again since. */
        final Set<UUID> loaded = new LinkedHashSet<>();
        /** The version of our own pack offered to them, or null. */
        UUID offered;
    }

    private final Map<UUID, Seen> players = new HashMap<>();
    private final Map<UUID, Set<String>> versions = new HashMap<>();
    private Mode mode = Mode.OWN;
    private String filter = "";
    private Set<String> merged = Set.of();

    // ---- policy ----

    synchronized void policy(Mode mode, String urlContains) {
        this.mode = mode;
        this.filter = urlContains == null ? "" : urlContains.trim();
    }

    synchronized Mode mode() {
        return mode;
    }

    /** One of our own pack versions (its id) and the skins it holds. */
    synchronized void version(UUID id, Collection<String> keys) {
        versions.put(id, Set.copyOf(keys));
    }

    synchronized boolean ours(UUID pack) {
        return pack != null && versions.containsKey(pack);
    }

    /** merged mode: the skins the server's pack holds. */
    synchronized void merged(Collection<String> keys) {
        merged = Set.copyOf(keys);
    }

    /** Forgets our versions no player has or was offered any more (except keep). */
    synchronized void prune(UUID keep) {
        Set<UUID> used = new HashSet<>();
        if (keep != null) used.add(keep);
        for (Seen s : players.values()) {
            if (s.offered != null) used.add(s.offered);
            used.addAll(s.loaded);
        }
        versions.keySet().retainAll(used);
    }

    // ---- facts ----

    /** The player's game answered about a pack (PlayerResourcePackStatusEvent, or the configuration phase's packet). */
    synchronized Result answer(UUID player, UUID pack, Status status) {
        if (player == null || pack == null || status == null) return Result.IGNORED;
        Seen s = players.computeIfAbsent(player, k -> new Seen());
        if (s.answers.put(pack, status) == status) return Result.IGNORED;
        boolean changed = switch (status) {
            case SUCCESSFULLY_LOADED -> s.loaded.add(pack);
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> s.loaded.remove(pack);
            // ACCEPTED and DOWNLOADED: still on its way, the final answer follows.
            default -> false;
        };
        return changed ? Result.CHANGED : Result.NOTED;
    }

    /** A pack was offered to the player (seen on the wire): its link noted; offered again, it is not loaded until the game says so. */
    synchronized void pushed(UUID player, UUID pack, String url) {
        if (player == null || pack == null) return;
        Seen s = players.computeIfAbsent(player, k -> new Seen());
        s.urls.put(pack, url == null ? "" : url);
        s.answers.remove(pack);
        s.loaded.remove(pack);
    }

    /**
     * A pack (null: every pack) was taken from the player's game; the game sends no answer for that.
     * Returns true when that took away the version of our pack offered to them.
     */
    synchronized boolean popped(UUID player, UUID pack) {
        Seen s = players.get(player);
        if (s == null) return false;
        boolean ours;
        if (pack == null) {
            ours = s.offered != null;
            s.urls.clear();
            s.answers.clear();
            s.loaded.clear();
            s.offered = null;
        } else {
            ours = pack.equals(s.offered);
            s.urls.remove(pack);
            s.answers.remove(pack);
            s.loaded.remove(pack);
            if (ours) s.offered = null;
        }
        return ours;
    }

    /** We offered the player this version of our pack (null: none now). */
    synchronized void offered(UUID player, UUID version) {
        players.computeIfAbsent(player, k -> new Seen()).offered = version;
    }

    synchronized UUID offered(UUID player) {
        Seen s = players.get(player);
        return s == null ? null : s.offered;
    }

    /** The last answer about this pack, or null. */
    synchronized Status answerAbout(UUID player, UUID pack) {
        Seen s = players.get(player);
        return s == null || pack == null ? null : s.answers.get(pack);
    }

    /** They left: their game drops the server's packs, and a rejoin starts over. */
    synchronized void forget(UUID player) {
        players.remove(player);
    }

    // ---- what players have ----

    /** The skins this player's game has the models of now. */
    synchronized Set<String> modelKeys(UUID player) {
        Seen s = players.get(player);
        if (s == null) return Set.of();
        Set<String> keys = new TreeSet<>();
        for (UUID pack : s.loaded) keys.addAll(keysOf(s, pack));
        return keys;
    }

    synchronized boolean hasModels(UUID player, String key) {
        Seen s = players.get(player);
        if (s == null || key == null) return false;
        for (UUID pack : s.loaded) {
            if (keysOf(s, pack).contains(key)) return true;
        }
        return false;
    }

    /** Whether the player has a pack loaded that counts now (one of ours in own mode, the server's in merged). */
    synchronized boolean hasPack(UUID player) {
        Seen s = players.get(player);
        if (s == null) return false;
        for (UUID pack : s.loaded) {
            if (counts(s, pack)) return true;
        }
        return false;
    }

    private Set<String> keysOf(Seen s, UUID pack) {
        return counts(s, pack) ? (mode == Mode.OWN ? versions.get(pack) : merged) : Set.of();
    }

    private boolean counts(Seen s, UUID pack) {
        Set<String> own = versions.get(pack);
        if (mode == Mode.OWN) return own != null;
        if (own != null) return false;
        if (filter.isEmpty()) return true;
        String url = s.urls.get(pack);
        return url != null && url.contains(filter);
    }

    // ---- what players need ----

    /** What the player needs so that their game is offered current (our own pack's id now, null for none). */
    synchronized Action plan(UUID player, UUID current) {
        UUID offered = offered(player);
        if (current == null) return offered == null ? Action.NOTHING : Action.REMOVE;
        if (current.equals(offered)) return Action.NOTHING;
        return offered == null ? Action.SEND : Action.REPLACE;
    }

    // ---- hot swap ----

    /**
     * For the next copy after a hot swap: this server run, our versions in use with their skins, and
     * each online player's session (their entity id, new on every join) with what their game has.
     */
    synchronized void save(ConfigurationSection out, long run, Map<UUID, Integer> sessions) {
        out.set("run", run);
        out.set("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        Set<UUID> used = new HashSet<>();
        sessions.forEach((player, session) -> {
            String key = "players." + player;
            out.set(key + ".session", session);
            Seen s = players.get(player);
            if (s == null) return;
            if (s.offered != null) {
                out.set(key + ".offered", s.offered.toString());
                used.add(s.offered);
            }
            Set<UUID> packs = new LinkedHashSet<>(s.urls.keySet());
            packs.addAll(s.answers.keySet());
            packs.addAll(s.loaded);
            for (UUID pack : packs) {
                String p = key + ".packs." + pack;
                out.set(p + ".url", s.urls.getOrDefault(pack, ""));
                Status answer = s.answers.get(pack);
                if (answer != null) out.set(p + ".answer", answer.name());
                out.set(p + ".loaded", s.loaded.contains(pack));
                if (s.loaded.contains(pack)) used.add(pack);
            }
        });
        for (UUID id : used) {
            Set<String> keys = versions.get(id);
            if (keys != null) out.set("versions." + id, new ArrayList<>(new TreeSet<>(keys)));
        }
    }

    /**
     * On enable, for a player already online: what the previous copy knew of them in this same session
     * (same server run and entity id) is restored, so nobody is asked again and nobody's NPCs change.
     * False when it knew nothing of this session (joined during the swap, or no saved state): then their
     * game has none of our packs, as far as we know.
     */
    synchronized boolean resume(ConfigurationSection saved, long run, UUID player, int session) {
        if (saved == null) return false;
        String key = "players." + player;
        if (saved.getLong("run") != run || saved.getInt(key + ".session", -1) != session) return false;
        Seen s = new Seen();
        s.offered = uuid(saved.getString(key + ".offered"));
        ConfigurationSection packs = saved.getConfigurationSection(key + ".packs");
        if (packs != null) {
            for (String id : packs.getKeys(false)) {
                UUID pack = uuid(id);
                if (pack == null) continue;
                s.urls.put(pack, packs.getString(id + ".url", ""));
                String answer = packs.getString(id + ".answer");
                if (answer != null) {
                    try {
                        s.answers.put(pack, Status.valueOf(answer));
                    } catch (IllegalArgumentException ignored) {
                        // an answer this server does not know: as if none
                    }
                }
                if (packs.getBoolean(id + ".loaded")) s.loaded.add(pack);
            }
        }
        ConfigurationSection saveVersions = saved.getConfigurationSection("versions");
        if (saveVersions != null) {
            for (String id : saveVersions.getKeys(false)) {
                UUID version = uuid(id);
                if (version == null || versions.containsKey(version)) continue;
                if (!Objects.equals(version, s.offered) && !s.loaded.contains(version)) continue;
                List<String> keys = saveVersions.getStringList(id);
                versions.put(version, Set.copyOf(keys));
            }
        }
        players.put(player, s);
        return true;
    }

    private static UUID uuid(String id) {
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
