package dev.livelynpcs.npc;

import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.anim.Animation;
import dev.livelynpcs.anim.AnimationService;
import dev.livelynpcs.pack.PackService;
import dev.livelynpcs.skin.SkinService;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/** The NPC runtime: npcs.yml, drawing, looks, animations, clicks. OWNER: the runtime agent. Stub until built. */
public final class NpcManager implements NpcService {

    private final LivelyNpcs plugin;

    public NpcManager(LivelyNpcs plugin, SkinService skins, AnimationService animations, PackService pack) {
        this.plugin = plugin;
    }

    /** Reads npcs.yml, draws the NPCs and starts the step loop; called once on enable. */
    public void start() { }

    /** Takes every NPC off every screen and stops; called on disable. */
    public void stop() { }

    @Override public Collection<NpcDef> all() { return List.of(); }
    @Override public NpcDef get(String id) { return null; }
    @Override public void put(NpcDef npc) throws IOException { throw new IOException("not built yet"); }
    @Override public boolean remove(String id) { return false; }
    @Override public boolean play(String npcId, String animationId, Player player) { return false; }
    @Override public boolean preview(String npcId, Animation draft, Player player) { return false; }
    @Override public void stopPreview(Player player) { }
    @Override public void reload() { }
    @Override public void addListener(Runnable onChange) { }
}
