package dev.livelynpcs.pack;

import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.skin.SkinService;
import dev.livelynpcs.web.WebServer;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/** Builds, hosts, sends or exports the NPC pack and tracks who has it. OWNER: the pack/skins/web agent. Stub until built. */
public final class PackManager implements PackService {

    private final LivelyNpcs plugin;

    public PackManager(LivelyNpcs plugin, SkinService skins, WebServer web) {
        this.plugin = plugin;
    }

    @Override public boolean hasModels(Player player, String skinKey) { return false; }
    @Override public void addListener(Consumer<Player> changed) { }
    @Override public void rebuild(Collection<String> skinKeys) { }
    @Override public void confirmMerged() { }
    @Override public List<String> status() { return List.of("not built yet"); }
    @Override public void start() { }
    @Override public void stop() { }
    @Override public void reload() { }
}
