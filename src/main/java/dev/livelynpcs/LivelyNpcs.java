package dev.livelynpcs;

import com.github.retrooper.packetevents.PacketEvents;
import dev.livelynpcs.anim.AnimationStore;
import dev.livelynpcs.command.NpcCommand;
import dev.livelynpcs.npc.NpcManager;
import dev.livelynpcs.pack.PackManager;
import dev.livelynpcs.skin.SkinStore;
import dev.livelynpcs.web.EditorApi;
import dev.livelynpcs.web.WebServer;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Lively NPCs: NPCs that look at you, wave, talk and move, drawn with packets (nothing exists or
 * ticks in the world), with a web editor for NPCs and animations. Players whose game has loaded the
 * NPC models (a resource pack) see each NPC as a moving model; everyone else sees a player model.
 * Safe to hot-swap (PlugManX): everything is saved as it changes, and players online are picked up
 * on enable. See DESIGN.md for how the parts fit together.
 */
public final class LivelyNpcs extends JavaPlugin {

    private Settings settings;
    private SkinStore skins;
    private AnimationStore animations;
    private WebServer web;
    private PackManager pack;
    private NpcManager npcs;
    private EditorApi editor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = readSettings();
        EntityLib.init(new SpigotEntityLibPlatform(this),
                new APIConfig(PacketEvents.getAPI()).usePlatformLogger().disableBStats().forceBundles());

        skins = new SkinStore(this);
        skins.reload();
        animations = new AnimationStore(this);
        animations.reload();
        web = new WebServer(this);
        if (settings.webEnabled) {
            try {
                web.start();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Web server could not start on port " + settings.webPort
                        + " (" + e.getMessage() + "): no editor, and no builtin pack host", e);
            }
        }
        pack = new PackManager(this, skins, web);
        pack.start();
        npcs = new NpcManager(this, skins, animations, pack);
        npcs.start();
        editor = new EditorApi(this, web, npcs, animations, skins, pack);
        if (web.running()) editor.register();

        NpcCommand command = new NpcCommand(this, npcs, animations, skins, pack, web);
        PluginCommand npc = getCommand("npc");
        if (npc != null) {
            npc.setExecutor(command);
            npc.setTabCompleter(command);
        }
        getLogger().info(npcs.all().size() + " NPCs, " + animations.all().size() + " animations, " + skins.all().size() + " skins"
                + (web.running() ? "; editor at " + web.baseUrl() + "/editor/" : ""));
    }

    @Override
    public void onDisable() {
        if (npcs != null) npcs.stop();
        if (pack != null) pack.stop();
        if (web != null) web.stop();
    }

    /** /npc reload: config.yml (except the web port), skins, animations, the pack and the NPCs. */
    public void reload() {
        reloadConfig();
        settings = readSettings();
        skins.reload();
        animations.reload();
        pack.reload();
        npcs.reload();
    }

    private Settings readSettings() {
        Settings s = new Settings(getConfig());
        for (String problem : s.problems) getLogger().warning("config.yml " + problem);
        return s;
    }

    /** The settings now (a new instance after each reload: never keep one). */
    public Settings settings() {
        return settings;
    }
}
