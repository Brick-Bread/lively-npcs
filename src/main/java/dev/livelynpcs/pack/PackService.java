package dev.livelynpcs.pack;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * The resource pack with the NPC models (see DESIGN.md, "Pack"). In own mode Lively NPCs builds it,
 * sends it to every player on join (beside any other server pack) and re-sends it when its skins
 * change; in merged mode it writes the models to plugins/LivelyNPCs/export/ for the server's own
 * pack and only watches whether players load that pack. Main thread unless said otherwise.
 */
public interface PackService {

    /** The item model id of a skin's body part, as the pack names it: "livelynpcs:&lt;key&gt;/&lt;part&gt;". */
    static String itemModel(String skinKey, String part) {
        return "livelynpcs:" + skinKey + "/" + part;
    }

    /**
     * Whether this player's game has the models for this skin loaded now: they loaded the pack, and
     * the pack they loaded has that skin. NPCs whose skin this is false for are drawn as player models.
     */
    boolean hasModels(Player player, String skinKey);

    /**
     * Called on the main thread whenever what {@link #hasModels} says for a player may have changed
     * (they loaded a pack, declined it, lost it, or a new version reached them).
     */
    void addListener(Consumer<Player> changed);

    /**
     * The skins in use changed (an NPC was given a new skin, or removed): rebuild the pack with these
     * skin keys. Own mode re-sends it to everyone online once built; merged mode rewrites export/.
     * Building runs off the main thread; nothing changes for players until it is done.
     */
    void rebuild(java.util.Collection<String> skinKeys);

    /**
     * merged mode: the admin says the server's pack now carries everything in export/ (/npc pack
     * merged). Until then skins added since the last confirmation are not drawn as models.
     */
    void confirmMerged();

    /** Lines for /npc pack: mode, host or link, SHA-1, skins in it, players who have it. */
    List<String> status();

    /** Starts on enable (after the web server); stop on disable. */
    void start();

    void stop();

    /** /npc reload: settings may have changed (mode, host, link). */
    void reload();
}
