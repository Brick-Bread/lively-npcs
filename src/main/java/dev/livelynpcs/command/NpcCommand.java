package dev.livelynpcs.command;

import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.anim.AnimationService;
import dev.livelynpcs.npc.NpcService;
import dev.livelynpcs.pack.PackService;
import dev.livelynpcs.skin.SkinService;
import dev.livelynpcs.web.WebServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;

/** /npc and its subcommands. OWNER: the runtime agent. Stub until built. */
public final class NpcCommand implements TabExecutor {

    private final LivelyNpcs plugin;

    public NpcCommand(LivelyNpcs plugin, NpcService npcs, AnimationService animations, SkinService skins, PackService pack,
                      WebServer web) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage("Lively NPCs is being built.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
