package dev.livelynpcs.npc;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One NPC as npcs.yml keeps it (and the editor's JSON sends it), see DESIGN.md "npcs.yml". Immutable:
 * a change is a new NpcDef put through NpcService.
 *
 * @param id      unique id (a-z, 0-9, _ and -): names it in commands and files.
 * @param entity  "player" for a person (skin, model, animations), or a mob type ("villager",
 *                "copper_golem", ...), which only looks at people and nods.
 * @param variant a villager's "type/profession" (e.g. "plains/farmer"), else "".
 * @param skin    a player NPC's skin key (skins.yml), or "" for the game's default skin (then no
 *                model: everyone sees the player model).
 * @param plate   lines floating above its head (MiniMessage, {npc} = its id); empty for no plate.
 * @param icon    an item floating, turning, above the plate ("" none).
 * @param held    an item in its left hand ("" none).
 * @param click   what a click does, in order: "player:&lt;command&gt;" (run as the player),
 *                "console:&lt;command&gt;", "message:&lt;MiniMessage&gt;"; {player} becomes the clicker's name.
 * @param idle    animations it plays now and then (empty = the built-in idle mix, ["none"] = none).
 * @param greet   animation when a player walks up ("" none).
 * @param clicked animation when clicked ("" none).
 * @param beckon  animation to beckon players over ("" = never beckons).
 * @param look    whether it turns to look at players.
 */
public record NpcDef(String id, String entity, String variant, String world, double x, double y, double z, float yaw,
                     String skin, List<String> plate, String icon, String held, List<String> click, List<String> idle,
                     String greet, String clicked, String beckon, boolean look) {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,40}");
    public static final String DEFAULT_GREET = "greet", DEFAULT_CLICKED = "talk", DEFAULT_BECKON = "beckon";

    public NpcDef {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("'" + id + "' is not an NPC id (a-z, 0-9, _ and -)");
        if (entity == null || entity.isBlank()) entity = "player";
        if (variant == null) variant = "";
        if (world == null || world.isBlank()) throw new IllegalArgumentException(id + ": no world");
        if (skin == null) skin = "";
        plate = plate == null ? List.of() : List.copyOf(plate);
        if (icon == null) icon = "";
        if (held == null) held = "";
        click = click == null ? List.of() : List.copyOf(click);
        idle = idle == null ? List.of() : List.copyOf(idle);
        if (greet == null) greet = DEFAULT_GREET;
        if (clicked == null) clicked = DEFAULT_CLICKED;
        if (beckon == null) beckon = DEFAULT_BECKON;
    }

    /** A new player NPC standing here with the default look and animations. */
    public static NpcDef create(String id, String world, double x, double y, double z, float yaw) {
        return new NpcDef(id, "player", "", world, x, y, z, yaw, "", List.of("<bold>" + id), "", "", List.of(), List.of(),
                DEFAULT_GREET, DEFAULT_CLICKED, DEFAULT_BECKON, true);
    }

    public boolean person() {
        return entity.equals("player");
    }

    public NpcDef at(String world, double x, double y, double z, float yaw) {
        return new NpcDef(id, entity, variant, world, x, y, z, yaw, skin, plate, icon, held, click, idle, greet, clicked, beckon, look);
    }

    public NpcDef withSkin(String skin) {
        return new NpcDef(id, entity, variant, world, x, y, z, yaw, skin, plate, icon, held, click, idle, greet, clicked, beckon, look);
    }
}
