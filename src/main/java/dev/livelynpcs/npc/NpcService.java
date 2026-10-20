package dev.livelynpcs.npc;

import dev.livelynpcs.anim.Animation;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Collection;

/**
 * The NPCs: kept in plugins/LivelyNPCs/npcs.yml, drawn with packets for everyone in their world.
 * Commands and the web editor change them only through here. Main thread only (the editor's web
 * threads hop to the main thread first).
 */
public interface NpcService {

    /** Every NPC, sorted by id. */
    Collection<NpcDef> all();

    /** The NPC with this id, or null. */
    NpcDef get(String id);

    /**
     * Creates or replaces the NPC with npc.id(): saves npcs.yml and redraws it for everyone who sees
     * it. A new skin key asks the pack for a rebuild. Throws IllegalArgumentException for an unknown
     * world, skin or item (the message is for people).
     */
    void put(NpcDef npc) throws IOException;

    /** Removes the NPC and saves; false if there was none. */
    boolean remove(String id) throws IOException;

    /**
     * Plays a saved animation on the NPC: for one player, or for everyone who sees it when player is
     * null. False if there is no such NPC or animation, or it is a mob.
     */
    boolean play(String npcId, String animationId, Player player);

    /**
     * Plays an unsaved animation (the editor's draft) on the NPC for this player only, round and
     * round if it loops, until stopPreview or the next preview replaces it.
     */
    boolean preview(String npcId, Animation draft, Player player);

    /** Ends this player's preview; the NPC carries on as before. */
    void stopPreview(Player player);

    /** Re-reads npcs.yml and redraws everything (after /npc reload). */
    void reload();

    /** Called on the main thread after any put, remove or reload. */
    void addListener(Runnable onChange);
}
