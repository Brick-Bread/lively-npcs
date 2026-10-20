package dev.livelynpcs.command;

import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.anim.Animation;
import dev.livelynpcs.anim.AnimationService;
import dev.livelynpcs.anim.AnimationStore;
import dev.livelynpcs.npc.ClickAction;
import dev.livelynpcs.npc.Mobs;
import dev.livelynpcs.npc.NpcDef;
import dev.livelynpcs.npc.NpcManager;
import dev.livelynpcs.npc.NpcService;
import dev.livelynpcs.pack.PackService;
import dev.livelynpcs.rig.Look;
import dev.livelynpcs.skin.Skin;
import dev.livelynpcs.skin.SkinService;
import dev.livelynpcs.web.WebServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;

/**
 * /npc: make, change and remove NPCs, play animations, open the web editor, see the pack, reload.
 * Everything needs livelynpcs.admin except /npc editor (livelynpcs.editor). Every argument has tab
 * completion. Messages are short MiniMessage lines with one prefix; text people typed is escaped.
 */
public final class NpcCommand implements TabExecutor {

    private static final String PREFIX = "<#6fd3a8>Lively NPCs</#6fd3a8> <dark_gray>»</dark_gray> <gray>";
    private static final String ADMIN = "livelynpcs.admin", EDITOR = "livelynpcs.editor";
    private static final List<String> SUBCOMMANDS = List.of("help", "create", "remove", "list", "tp", "movehere", "turn", "skin",
            "item", "icon", "plate", "click", "anim", "look", "entity", "play", "editor", "pack", "reload");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final LivelyNpcs plugin;
    private final NpcService npcs;
    private final AnimationService animations;
    private final SkinService skins;
    private final PackService pack;
    private final WebServer web;

    public NpcCommand(LivelyNpcs plugin, NpcService npcs, AnimationService animations, SkinService skins, PackService pack,
                      WebServer web) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.animations = animations;
        this.skins = skins;
        this.pack = pack;
        this.web = web;
    }

    // ------------------------------------------------------------------ running

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(sub.equals("editor") ? EDITOR : ADMIN)) {
            say(sender, "<red>You do not have permission for that.");
            return true;
        }
        try {
            switch (sub) {
                case "create" -> create(sender, args);
                case "remove", "delete" -> remove(sender, args);
                case "list" -> list(sender);
                case "tp" -> tp(sender, args);
                case "movehere" -> moveHere(sender, args);
                case "turn" -> turn(sender, args);
                case "skin" -> skin(sender, args);
                case "item", "held" -> item(sender, args, true);
                case "icon" -> item(sender, args, false);
                case "plate" -> plate(sender, args);
                case "click" -> click(sender, args);
                case "anim" -> anim(sender, args);
                case "look" -> look(sender, args);
                case "entity" -> entity(sender, args);
                case "play" -> play(sender, args);
                case "editor" -> editor(sender);
                case "pack" -> pack(sender, args);
                case "reload" -> reload(sender);
                case "help" -> help(sender);
                default -> say(sender, "No command <white>" + esc(sub) + "</white>. <click:run_command:'/npc help'><u>/npc help</u></click> lists them.");
            }
        } catch (Stop stop) {
            say(sender, stop.getMessage());
        }
        return true;
    }

    /** Ends a command with a message (MiniMessage) for the sender. */
    private static final class Stop extends RuntimeException {
        Stop(String message) {
            super(message, null, false, false);
        }
    }

    private void help(CommandSender s) {
        say(s, "<white>Commands</white> <dark_gray>(click one to fill it in)");
        line(s, "/npc create ", "<id>", "a new NPC where you stand");
        line(s, "/npc remove ", "<id>", "");
        line(s, "/npc list", "", "every NPC; click one to go there");
        line(s, "/npc tp ", "<id>", "go to it");
        line(s, "/npc movehere ", "<id>", "move it to you");
        line(s, "/npc turn ", "<id> <yaw|me>", "which way it faces");
        line(s, "/npc skin ", "<id> <player name>", "or: skin <id> url <link> [slim]");
        line(s, "/npc item ", "<id> <item|none>", "in its left hand");
        line(s, "/npc icon ", "<id> <item|none>", "turning over its plate");
        line(s, "/npc plate ", "<id> <line 1> | <line 2> | ...", "MiniMessage; none for no plate");
        line(s, "/npc click ", "<id> add <player|console|message> <text>", "or: click <id> clear|list");
        line(s, "/npc anim ", "<id> idle <animations...|default|none>", "");
        line(s, "/npc anim ", "<id> <greet|clicked|beckon> <animation|none>", "");
        line(s, "/npc look ", "<id> <true|false>", "turn to look at players");
        line(s, "/npc entity ", "<id> <player|mob> [variant]", "a person, or a villager, cow, ...");
        line(s, "/npc play ", "<id> <animation|none> [player|all]", "play one now");
        line(s, "/npc editor", "", "the web editor, in your browser");
        line(s, "/npc pack", " [merged]", "the NPC models' resource pack");
        line(s, "/npc reload", "", "config.yml, npcs.yml, skins, animations");
    }

    private void line(CommandSender s, String command, String args, String about) {
        s.sendMessage(MM.deserialize("<gray> <click:suggest_command:'" + command + "'><hover:show_text:'Click to fill in'><#6fd3a8>"
                + esc(command.trim()) + "</#6fd3a8> <white>" + esc(args) + "</white></hover></click>"
                + (about.isEmpty() ? "" : " <dark_gray>- " + esc(about))));
    }

    private void create(CommandSender s, String[] a) {
        Player p = player(s);
        String id = arg(a, 1, "/npc create <id>").toLowerCase(Locale.ROOT);
        if (!NpcDef.ID.matcher(id).matches()) throw new Stop("<red>An NPC id is up to 40 of a-z, 0-9, _ and -, like <white>shop_keeper</white>.");
        if (npcs.get(id) != null) throw new Stop("<red>There is already an NPC called <white>" + id + "</white>.");
        Location at = p.getLocation();
        put(s, NpcDef.create(id, at.getWorld().getName(), round(at.getX(), 2), round(at.getY(), 2), round(at.getZ(), 2),
                (float) round(at.getYaw(), 1)));
        say(s, "Made <white>" + id + "</white> where you stand. Give it a skin: <click:suggest_command:'/npc skin " + id
                + " '><u>/npc skin " + id + " <player name></u></click>");
    }

    private void remove(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc remove <id>");
        try {
            npcs.remove(npc.id());
        } catch (IOException e) {
            throw new Stop("<red>Could not save npcs.yml: " + esc(e.getMessage()));
        }
        say(s, "Removed <white>" + npc.id() + "</white>.");
    }

    private void list(CommandSender s) {
        List<NpcDef> all = new ArrayList<>(npcs.all());
        if (all.isEmpty()) {
            say(s, "There are no NPCs yet. Make one where you stand: <click:suggest_command:'/npc create '><u>/npc create <id></u></click>");
            return;
        }
        say(s, "<white>" + all.size() + "</white> NPC" + (all.size() == 1 ? "" : "s") + " <dark_gray>(click one to go there)");
        for (NpcDef n : all) {
            String looks = "";
            if (s instanceof Player p && npcs instanceof NpcManager m) {
                String look = m.lookFor(n.id(), p);
                looks = look == null ? "" : " <dark_gray>you see a " + look;
            }
            String what = n.person() ? (n.skin().isEmpty() ? "player, default skin" : "player") : n.entity().replace('_', ' ');
            s.sendMessage(MM.deserialize("<gray> <click:run_command:'/npc tp " + n.id() + "'><hover:show_text:'Go to " + n.id()
                    + "'><white>" + n.id() + "</white></hover></click> <dark_gray>" + esc(what) + ", " + esc(n.world()) + " "
                    + Math.round(n.x()) + " " + Math.round(n.y()) + " " + Math.round(n.z()) + looks));
        }
    }

    private void tp(CommandSender s, String[] a) {
        Player p = player(s);
        NpcDef npc = npc(a, 1, "/npc tp <id>");
        World world = Bukkit.getWorld(npc.world());
        if (world == null) throw new Stop("<red>" + npc.id() + "'s world <white>" + esc(npc.world()) + "</white> is not loaded.");
        p.teleport(new Location(world, npc.x(), npc.y(), npc.z(), npc.yaw(), 0f));
        say(s, "Here is <white>" + npc.id() + "</white>.");
    }

    private void moveHere(CommandSender s, String[] a) {
        Player p = player(s);
        NpcDef npc = npc(a, 1, "/npc movehere <id>");
        Location at = p.getLocation();
        put(s, npc.at(at.getWorld().getName(), round(at.getX(), 2), round(at.getY(), 2), round(at.getZ(), 2), (float) round(at.getYaw(), 1)));
        say(s, "<white>" + npc.id() + "</white> now stands where you are, facing your way.");
    }

    private void turn(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc turn <id> <yaw|me>");
        String how = arg(a, 2, "/npc turn " + npc.id() + " <yaw|me>");
        float yaw;
        if (how.equalsIgnoreCase("me")) {
            Player p = player(s);
            if (!p.getWorld().getName().equals(npc.world())) throw new Stop("<red>You are not in " + npc.id() + "'s world.");
            yaw = Look.yaw(p.getX() - npc.x(), p.getZ() - npc.z());
        } else {
            try {
                yaw = Float.parseFloat(how);
            } catch (NumberFormatException e) {
                throw new Stop("<red>Give a yaw (0 faces south, 90 west, 180 north, -90 east) or <white>me</white>.");
            }
            if (!Float.isFinite(yaw)) throw new Stop("<red>That is not a direction.");
        }
        yaw = Look.wrap(yaw);
        put(s, npc.at(npc.world(), npc.x(), npc.y(), npc.z(), (float) round(yaw, 1)));
        say(s, "<white>" + npc.id() + "</white> now faces " + (how.equalsIgnoreCase("me") ? "you" : "yaw " + round(yaw, 1)) + ".");
    }

    private void skin(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc skin <id> <player name>");
        if (!npc.person()) throw new Stop("<red>" + npc.id() + " is a " + esc(npc.entity()) + ": only player NPCs wear skins.");
        String what = arg(a, 2, "/npc skin " + npc.id() + " <player name>  or  url <link> [slim]");
        CompletableFuture<Skin> future;
        String from;
        if (what.equalsIgnoreCase("url") && a.length > 3) {
            String link = a[3];
            boolean slim = a.length > 4 && a[4].equalsIgnoreCase("slim");
            from = "that image" + (slim ? " (slim arms)" : "");
            say(s, "Getting the skin from that link and having it signed by MineSkin...");
            future = skins.fromUrl(link, slim);
        } else {
            if (!what.matches("[A-Za-z0-9_]{1,16}")) throw new Stop("<red>'" + esc(what) + "' is not a player name.");
            from = what + "'s skin";
            say(s, "Getting <white>" + esc(what) + "</white>'s skin from Mojang...");
            future = skins.fromPlayer(what);
        }
        String id = npc.id();
        future.whenComplete((skin, failure) -> onMain(() -> {
            if (failure != null || skin == null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                say(s, "<red>Could not get the skin: " + esc(cause == null ? "no skin came back" : String.valueOf(cause.getMessage())));
                return;
            }
            NpcDef now = npcs.get(id);
            if (now == null) {
                say(s, "<red>" + id + " was removed while its skin was on the way.");
                return;
            }
            try {
                npcs.put(now.withSkin(skin.key()));
            } catch (IllegalArgumentException | IOException e) {
                say(s, "<red>Could not give " + id + " the skin: " + esc(e.getMessage()));
                return;
            }
            say(s, "<white>" + id + "</white> now wears " + esc(from) + (skin.slim() ? " <dark_gray>(slim arms)" : "") + ".");
        }));
    }

    private void item(CommandSender s, String[] a, boolean held) {
        String usage = held ? "/npc item <id> <item|none>" : "/npc icon <id> <item|none>";
        NpcDef npc = npc(a, 1, usage);
        String item = itemId(arg(a, 2, usage));
        put(s, edit(npc, e -> held ? e.held(item) : e.icon(item)));
        if (item.isEmpty()) say(s, "<white>" + npc.id() + "</white> " + (held ? "holds nothing now." : "has no icon now."));
        else say(s, "<white>" + npc.id() + "</white> " + (held ? "now holds " : "now has the icon ") + "<white>" + esc(item) + "</white>.");
    }

    private void plate(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc plate <id> <line 1> | <line 2> | ...");
        String text = join(a, 2);
        if (text.isEmpty()) throw new Stop("<red>Use <white>/npc plate " + npc.id() + " <line 1> | <line 2> | ...</white> (MiniMessage), or none.");
        List<String> lines = text.equalsIgnoreCase("none") ? List.of() : Arrays.stream(text.split("\\s*\\|\\s*")).filter(l -> !l.isEmpty()).toList();
        put(s, edit(npc, e -> e.plate(lines)));
        if (lines.isEmpty()) {
            say(s, "<white>" + npc.id() + "</white> has no plate now.");
            return;
        }
        say(s, "<white>" + npc.id() + "</white>'s plate is now:");
        for (String l : lines) s.sendMessage(Component.text("  ").append(MM.deserialize(l.replace("{npc}", npc.id()))));
    }

    private void click(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc click <id> add|clear|list");
        String what = arg(a, 2, "/npc click " + npc.id() + " add <player|console|message> <text>  or  clear  or  list").toLowerCase(Locale.ROOT);
        switch (what) {
            case "add" -> {
                String kind = arg(a, 3, "/npc click " + npc.id() + " add <player|console|message> <text>").toLowerCase(Locale.ROOT);
                String text = join(a, 4);
                ClickAction action;
                try {
                    action = ClickAction.parse(kind + ":" + text);
                } catch (IllegalArgumentException e) {
                    throw new Stop("<red>" + esc(e.getMessage()) + ". Use <white>/npc click " + npc.id() + " add <player|console|message> <text></white>.");
                }
                List<String> click = new ArrayList<>(npc.click());
                click.add(action.toString());
                put(s, edit(npc, e -> e.click(click)));
                say(s, "A click on <white>" + npc.id() + "</white> now also does: <white>" + esc(action.toString()) + "</white>"
                        + " <dark_gray>({player} is whoever clicks)");
            }
            case "clear" -> {
                put(s, edit(npc, e -> e.click(List.of())));
                say(s, "A click on <white>" + npc.id() + "</white> does nothing now (it still talks).");
            }
            case "list" -> {
                if (npc.click().isEmpty()) {
                    say(s, "A click on <white>" + npc.id() + "</white> does nothing yet.");
                    return;
                }
                say(s, "A click on <white>" + npc.id() + "</white>, in order:");
                for (int i = 0; i < npc.click().size(); i++) {
                    s.sendMessage(MM.deserialize("<gray>  " + (i + 1) + ". <white>" + esc(npc.click().get(i))));
                }
            }
            default -> throw new Stop("<red>Use add, clear or list: <white>/npc click " + npc.id() + " add <player|console|message> <text></white>.");
        }
    }

    private void anim(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc anim <id> idle|greet|clicked|beckon ...");
        if (!npc.person()) throw new Stop("<red>" + npc.id() + " is a " + esc(npc.entity()) + ": mobs only look at people and nod.");
        String which = arg(a, 2, "/npc anim " + npc.id() + " idle|greet|clicked|beckon ...").toLowerCase(Locale.ROOT);
        switch (which) {
            case "idle" -> {
                List<String> ids = new ArrayList<>();
                for (int i = 3; i < a.length; i++) ids.addAll(Arrays.asList(a[i].toLowerCase(Locale.ROOT).split(",")));
                ids.removeIf(String::isBlank);
                if (ids.isEmpty()) throw new Stop("<red>Use <white>/npc anim " + npc.id() + " idle <animations...|default|none></white>.");
                List<String> idle = ids.equals(List.of("default")) ? List.of() : ids;
                put(s, edit(npc, e -> e.idle(idle)));
                say(s, "<white>" + npc.id() + "</white> " + (idle.isEmpty() ? "plays the built-in idle mix now and then."
                        : idle.equals(List.of(AnimationStore.NONE)) ? "plays no idle animations now."
                        : "now and then plays: <white>" + esc(String.join(", ", idle)) + "</white>."));
            }
            case "greet", "clicked", "beckon" -> {
                String id = arg(a, 3, "/npc anim " + npc.id() + " " + which + " <animation|none>").toLowerCase(Locale.ROOT);
                String value = id.equals(AnimationStore.NONE) ? "" : id;
                put(s, edit(npc, e -> switch (which) {
                    case "greet" -> e.greet(value);
                    case "clicked" -> e.clicked(value);
                    default -> e.beckon(value);
                }));
                String when = switch (which) {
                    case "greet" -> "when someone walks up";
                    case "clicked" -> "when clicked";
                    default -> "to beckon people over";
                };
                say(s, "<white>" + npc.id() + "</white> " + (value.isEmpty() ? "plays nothing " + when + " now."
                        : "plays <white>" + esc(value) + "</white> " + when + "."));
            }
            default -> throw new Stop("<red>Use idle, greet, clicked or beckon: <white>/npc anim " + npc.id() + " greet wave</white>.");
        }
    }

    private void look(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc look <id> <true|false>");
        String v = arg(a, 2, "/npc look " + npc.id() + " <true|false>").toLowerCase(Locale.ROOT);
        boolean look = switch (v) {
            case "true", "on", "yes" -> true;
            case "false", "off", "no" -> false;
            default -> throw new Stop("<red>Use true or false.");
        };
        put(s, edit(npc, e -> e.look(look)));
        say(s, "<white>" + npc.id() + "</white> " + (look ? "turns to look at players now." : "keeps facing its own way now."));
    }

    private void entity(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc entity <id> <player|mob> [variant]");
        String type = arg(a, 2, "/npc entity " + npc.id() + " <player|villager|cow|...> [variant]").toLowerCase(Locale.ROOT);
        if (type.startsWith("minecraft:")) type = type.substring("minecraft:".length());
        String variant = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "";
        String entity = type;
        put(s, edit(npc, e -> e.entity(entity).variant(entity.equals("villager") ? variant : "")));
        say(s, "<white>" + npc.id() + "</white> is now " + (entity.equals("player") ? "a person"
                : "a " + esc(entity.replace('_', ' ')) + (entity.equals("villager") && !variant.isEmpty() ? " (" + esc(variant) + ")" : "")) + ".");
    }

    private void play(CommandSender s, String[] a) {
        NpcDef npc = npc(a, 1, "/npc play <id> <animation|none> [player|all]");
        if (!npc.person()) throw new Stop("<red>" + npc.id() + " is a " + esc(npc.entity()) + ": mobs only look at people and nod.");
        String id = arg(a, 2, "/npc play " + npc.id() + " <animation|none> [player|all]").toLowerCase(Locale.ROOT);
        Player target;
        String who;
        if (a.length > 3 && a[3].equalsIgnoreCase("all")) {
            target = null;
            who = "everyone";
        } else if (a.length > 3) {
            target = Bukkit.getPlayerExact(a[3]);
            if (target == null) throw new Stop("<red>" + esc(a[3]) + " is not online.");
            who = target.getName();
        } else if (s instanceof Player p) {
            target = p;
            who = "you";
        } else {
            target = null;
            who = "everyone";
        }
        if (id.equals(AnimationStore.NONE)) {
            if (npcs instanceof NpcManager m) m.stopPlaying(npc.id(), target);
            say(s, "<white>" + npc.id() + "</white> stopped what it was playing for " + esc(who) + ".");
            return;
        }
        Animation animation = animations.get(id);
        if (animation == null) throw new Stop("<red>There is no animation called <white>" + esc(id) + "</white>.");
        npcs.play(npc.id(), id, target);
        say(s, "<white>" + npc.id() + "</white> plays <white>" + esc(id) + "</white> for " + esc(who)
                + (animation.loop() ? ", round and round until <white>/npc play " + npc.id() + " none</white>" : "") + ".");
    }

    private void editor(CommandSender s) {
        Player p = player(s);
        if (!web.running()) {
            throw new Stop("<red>The web editor is off: the web server is not running. Turn on <white>web.enabled</white> in config.yml"
                    + " (with a free <white>web.port</white>), then restart the server. The console says why it did not start.");
        }
        WebServer.Session session = web.newSession(p.getUniqueId());
        String link = web.baseUrl() + "/editor/#" + session.token();
        int minutes = plugin.settings().editorMinutes;
        say(s, "<click:open_url:'" + link + "'><hover:show_text:'Open the editor in your browser'><#6fd3a8><u>Open the NPC editor</u></#6fd3a8>"
                + "</hover></click> <dark_gray>(your own link, for " + (minutes % 60 == 0 ? minutes / 60 + " hour" + (minutes == 60 ? "" : "s")
                : minutes + " minutes") + "; do not share it)");
    }

    private void pack(CommandSender s, String[] a) {
        if (a.length > 1 && a[1].equalsIgnoreCase("merged")) {
            pack.confirmMerged();
            say(s, "Noted: the server's own pack now has every NPC model in <white>export/</white>. Players who load it see the moving NPCs.");
            return;
        }
        say(s, "<white>The NPC models' pack</white>");
        for (String line : pack.status()) s.sendMessage(Component.text("  ", net.kyori.adventure.text.format.NamedTextColor.GRAY).append(MM.deserialize(line)));
    }

    private void reload(CommandSender s) {
        try {
            plugin.reload();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "/npc reload failed", e);
            throw new Stop("<red>Reload failed: " + esc(String.valueOf(e.getMessage())) + " (the console has more).");
        }
        say(s, "Reloaded: <white>" + npcs.all().size() + "</white> NPCs, <white>" + animations.all().size() + "</white> animations, <white>"
                + skins.all().size() + "</white> skins. Problems, if any, are in the console.");
    }

    // ------------------------------------------------------------------ helpers

    /** Saves a changed NPC; a problem with it (unknown world, item, animation...) stops the command with its message. */
    private void put(CommandSender s, NpcDef npc) {
        try {
            npcs.put(npc);
        } catch (IllegalArgumentException e) {
            throw new Stop("<red>" + esc(e.getMessage()));
        } catch (IOException e) {
            throw new Stop("<red>Could not save npcs.yml: " + esc(e.getMessage()));
        }
    }

    private NpcDef npc(String[] a, int i, String usage) {
        String id = arg(a, i, usage).toLowerCase(Locale.ROOT);
        NpcDef npc = npcs.get(id);
        if (npc == null) {
            throw new Stop("<red>There is no NPC called <white>" + esc(id) + "</white>. <click:run_command:'/npc list'><u>/npc list</u></click> shows them.");
        }
        return npc;
    }

    private static String arg(String[] a, int i, String usage) {
        if (a.length <= i || a[i].isBlank()) throw new Stop("Use <white>" + esc(usage) + "</white>");
        return a[i];
    }

    private static Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        throw new Stop("<red>Only a player can do that (it uses where you stand).");
    }

    /** An item id as npcs.yml keeps it: lower case, without minecraft:, '' for none. */
    private static String itemId(String typed) {
        String item = typed.toLowerCase(Locale.ROOT);
        if (item.startsWith("minecraft:")) item = item.substring("minecraft:".length());
        return item.equals(AnimationStore.NONE) ? "" : item;
    }

    private static String join(String[] a, int from) {
        return a.length <= from ? "" : String.join(" ", Arrays.copyOfRange(a, from, a.length)).trim();
    }

    private static double round(double v, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(v * scale) / scale;
    }

    private void onMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    private static void say(CommandSender to, String miniMessage) {
        to.sendMessage(MM.deserialize(PREFIX + miniMessage));
    }

    private static String esc(String text) {
        return MM.escapeTags(text == null ? "" : text);
    }

    /** A copy of an NPC with some settings changed. */
    private static NpcDef edit(NpcDef n, UnaryOperator<Edit> change) {
        return change.apply(new Edit(n)).done();
    }

    /** NpcDef's fields, to change one or two and make a new NpcDef. */
    private static final class Edit {
        private final NpcDef n;
        private String entity, variant, icon, held, greet, clicked, beckon;
        private List<String> plate, click, idle;
        private boolean look;

        Edit(NpcDef n) {
            this.n = n;
            entity = n.entity();
            variant = n.variant();
            icon = n.icon();
            held = n.held();
            greet = n.greet();
            clicked = n.clicked();
            beckon = n.beckon();
            plate = n.plate();
            click = n.click();
            idle = n.idle();
            look = n.look();
        }

        Edit entity(String v) { entity = v; return this; }
        Edit variant(String v) { variant = v; return this; }
        Edit icon(String v) { icon = v; return this; }
        Edit held(String v) { held = v; return this; }
        Edit greet(String v) { greet = v; return this; }
        Edit clicked(String v) { clicked = v; return this; }
        Edit beckon(String v) { beckon = v; return this; }
        Edit plate(List<String> v) { plate = v; return this; }
        Edit click(List<String> v) { click = v; return this; }
        Edit idle(List<String> v) { idle = v; return this; }
        Edit look(boolean v) { look = v; return this; }

        NpcDef done() {
            return new NpcDef(n.id(), entity, variant, n.world(), n.x(), n.y(), n.z(), n.yaw(), n.skin(), plate, icon, held, click, idle,
                    greet, clicked, beckon, look);
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return List.of();
        String last = args[args.length - 1];
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            for (String sub : SUBCOMMANDS) if (sender.hasPermission(sub.equals("editor") ? EDITOR : ADMIN)) subs.add(sub);
            return match(subs, last);
        }
        if (!sender.hasPermission(ADMIN)) return List.of();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (List.of("editor", "list", "reload", "help").contains(sub)) return List.of();
        if (sub.equals("pack")) return args.length == 2 ? match(List.of("merged"), last) : List.of();
        if (args.length == 2) return sub.equals("create") ? List.of() : match(npcIds(), last);
        NpcDef npc = npcs.get(args[1].toLowerCase(Locale.ROOT));
        int at = args.length - 1;
        return switch (sub) {
            case "turn" -> at == 2 ? match(List.of("me", "0", "90", "180", "-90"), last) : List.of();
            case "skin" -> {
                if (at == 2) {
                    List<String> names = new ArrayList<>(List.of("url"));
                    for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                    yield match(names, last);
                }
                if (args[2].equalsIgnoreCase("url")) yield at == 3 ? match(List.of("https://"), last) : at == 4 ? match(List.of("slim"), last) : List.of();
                yield List.of();
            }
            case "item", "held", "icon" -> at == 2 ? items(last) : List.of();
            case "plate" -> {
                if (at != 2) yield List.of();
                List<String> options = new ArrayList<>(List.of("none"));
                if (npc != null && !npc.plate().isEmpty()) options.add(String.join(" | ", npc.plate()));
                yield match(options, last);
            }
            case "click" -> {
                if (at == 2) yield match(List.of("add", "clear", "list"), last);
                if (at == 3 && args[2].equalsIgnoreCase("add")) yield match(List.of("player", "console", "message"), last);
                yield List.of();
            }
            case "anim" -> {
                if (at == 2) yield match(List.of("idle", "greet", "clicked", "beckon"), last);
                List<String> ids = animationIds();
                if (args[2].equalsIgnoreCase("idle")) {
                    if (at == 3) ids.addAll(0, List.of("default", "none"));
                    yield match(ids, last);
                }
                if (at == 3 && List.of("greet", "clicked", "beckon").contains(args[2].toLowerCase(Locale.ROOT))) {
                    ids.addFirst("none");
                    yield match(ids, last);
                }
                yield List.of();
            }
            case "look" -> at == 2 ? match(List.of("true", "false"), last) : List.of();
            case "entity" -> {
                if (at == 2) yield match(mobs(), last);
                if (at == 3 && args[2].equalsIgnoreCase("villager")) {
                    List<String> variants = new ArrayList<>();
                    for (String t : Mobs.VILLAGER_TYPES) {
                        variants.add(t);
                        for (String job : Mobs.PROFESSIONS) if (!job.equals("none")) variants.add(t + "/" + job);
                    }
                    yield match(variants, last);
                }
                yield List.of();
            }
            case "play" -> {
                if (at == 2) {
                    List<String> ids = animationIds();
                    ids.addFirst("none");
                    yield match(ids, last);
                }
                if (at == 3) {
                    List<String> who = new ArrayList<>(List.of("all"));
                    for (Player p : Bukkit.getOnlinePlayers()) who.add(p.getName());
                    yield match(who, last);
                }
                yield List.of();
            }
            default -> List.of();
        };
    }

    private List<String> npcIds() {
        List<String> ids = new ArrayList<>();
        for (NpcDef n : npcs.all()) ids.add(n.id());
        return ids;
    }

    private List<String> animationIds() {
        List<String> ids = new ArrayList<>();
        for (Animation a : animations.all()) ids.add(a.id());
        return ids;
    }

    /** Item ids starting with what was typed (none first), at most 80. */
    private static List<String> items(String typed) {
        String t = itemId(typed.isEmpty() ? "" : typed);
        if (typed.equalsIgnoreCase("none")) return List.of("none");
        List<String> out = new ArrayList<>();
        if ("none".startsWith(typed.toLowerCase(Locale.ROOT))) out.add("none");
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
            String key = m.getKey().getKey();
            if (key.startsWith(t)) out.add(key);
            if (out.size() >= 80) break;
        }
        return out;
    }

    /** player, then every living mob there is. */
    private static List<String> mobs() {
        List<String> out = new ArrayList<>(List.of("player"));
        for (EntityType type : Registry.ENTITY_TYPE) {
            if (type.isAlive() && type != EntityType.PLAYER) out.add(type.getKey().getKey());
        }
        return out;
    }

    private static List<String> match(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        return out;
    }
}
