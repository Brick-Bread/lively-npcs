package dev.livelynpcs.npc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** npcs.yml: DESIGN.md's example reads, everything survives a write and a read, bad values are reported. */
class NpcFileTest {

    /** The example in DESIGN.md "npcs.yml", comments and all. */
    private static final String DESIGN_EXAMPLE = """
            npcs:
              mobshop:                       # the id
                entity: player               # or a mob type: villager, copper_golem, ...
                variant: ''                  # villagers: type/profession, e.g. plains/farmer
                world: world
                x: 6.5
                y: 0
                z: -2.5
                yaw: 180
                skin: 3f2a9c0d11e4b7a2       # a skins.yml key ('' = default skin, player model only)
                plate: ['<gold><bold>Mob Shop', '<gray>Buy mobs', '<yellow>Click']
                icon: cow_spawn_egg          # floats over the plate, turning ('' none)
                held: wheat                  # in its left hand ('' none)
                click: ['player:shop mobs']  # player:<cmd>, console:<cmd>, message:<MiniMessage>; {player} = clicker
                idle: []                     # [] = the default idle mix; [none] = no idle animations
                greet: greet                 # '' = none
                clicked: talk
                beckon: beckon               # '' = never beckons
                look: true
            """;

    private static final NpcDef MOBSHOP = new NpcDef("mobshop", "player", "", "world", 6.5, 0, -2.5, 180, "3f2a9c0d11e4b7a2",
            List.of("<gold><bold>Mob Shop", "<gray>Buy mobs", "<yellow>Click"), "cow_spawn_egg", "wheat",
            List.of("player:shop mobs"), List.of(), "greet", "talk", "beckon", true);

    @Test
    void readsTheDesignExample() {
        NpcFile.Loaded loaded = NpcFile.read(DESIGN_EXAMPLE);
        assertEquals(List.of(), loaded.problems());
        assertEquals(List.of(MOBSHOP), loaded.npcs());
    }

    @Test
    void writesTheDesignShape() {
        String text = NpcFile.write(List.of(MOBSHOP));
        String body = text.substring(text.indexOf("npcs:"));
        assertEquals("""
                npcs:
                  mobshop:
                    entity: player
                    variant: ''
                    world: world
                    x: 6.5
                    y: 0
                    z: -2.5
                    yaw: 180
                    skin: 3f2a9c0d11e4b7a2
                    plate: [<gold><bold>Mob Shop, <gray>Buy mobs, <yellow>Click]
                    icon: cow_spawn_egg
                    held: wheat
                    click: ['player:shop mobs']
                    idle: []
                    greet: greet
                    clicked: talk
                    beckon: beckon
                    look: true
                """, body);
        assertTrue(text.startsWith("# Lively NPCs"), "a header for people editing it by hand");
    }

    @Test
    void everythingSurvivesAWriteAndARead() {
        List<NpcDef> npcs = List.of(
                MOBSHOP,
                new NpcDef("farmer-joe", "villager", "desert/farmer", "world_nether", -1234.5678, 64, 0.25, -37.5f, "",
                        List.of(), "", "", List.of("console:give {player} bread 1", "message:<green>Hi {player}, it's: \"great\" #1!"),
                        List.of("none"), "", "", "", false),
                new NpcDef("odd_values", "copper_golem", "", "my world", 0, -64, 1e6, 359.99f, "00000000000000aa",
                        List.of("a, b", "[brackets]", "{braces}", "'quotes'", "#hash", "yes", "123", "", " spaced "),
                        "diamond", "stick", List.of(), List.of("wave", "nod"), "hop", "nod", "wave", true));
        NpcFile.Loaded back = NpcFile.read(NpcFile.write(npcs));
        assertEquals(List.of(), back.problems());
        assertEquals(npcs.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList(), back.npcs());
    }

    @Test
    void numericLookingSkinKeysStayText() {
        NpcDef digits = MOBSHOP.withSkin("0000000000001234");
        NpcFile.Loaded back = NpcFile.read(NpcFile.write(List.of(digits)));
        assertEquals("0000000000001234", back.npcs().getFirst().skin());
    }

    @Test
    void badValuesAreReportedWithTheirPathAndDefaulted() {
        NpcFile.Loaded loaded = NpcFile.read("""
                npcs:
                  good:
                    world: world
                    x: here
                    y: 1
                    z: 2
                    look: maybe
                    plate: 7
                    colour: red
                  noworld:
                    x: 1
                  Bad Id:
                    world: world
                """);
        assertEquals(1, loaded.npcs().size());
        NpcDef good = loaded.npcs().getFirst();
        assertEquals(0, good.x());
        assertTrue(good.look());
        assertEquals(List.of("7"), good.plate());
        assertEquals("greet", good.greet(), "missing animations take the defaults");
        List<String> p = loaded.problems();
        assertTrue(p.contains("npcs.good.x: 'here' is not a number, using 0"), p.toString());
        assertTrue(p.contains("npcs.good.look: should be true or false, using true"), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.startsWith("npcs.good.colour: not a setting")), p.toString());
        assertTrue(p.contains("npcs.good.yaw: missing, using 0"), p.toString());
        assertTrue(p.contains("npcs.noworld.world: missing, so this NPC is left out"), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.startsWith("npcs.Bad Id: 'Bad Id' is not an NPC id")), p.toString());
    }

    @Test
    void brokenOrEmptyFiles() {
        assertEquals(List.of(), NpcFile.read("").npcs());
        assertEquals(List.of(), NpcFile.read("").problems());
        assertEquals(List.of(), NpcFile.read("npcs: {}\n").npcs());
        NpcFile.Loaded broken = NpcFile.read("npcs:\n  a: [unclosed\n");
        assertEquals(1, broken.problems().size());
        assertTrue(broken.problems().getFirst().startsWith("is not valid YAML"), broken.problems().toString());
        assertEquals(List.of("npcs: should be a map of NPCs by id"), NpcFile.read("npcs: [1, 2]\n").problems());
        assertEquals("npcs: {}\n", NpcFile.write(List.of()).substring(NpcFile.write(List.of()).indexOf("npcs:")));
    }
}
