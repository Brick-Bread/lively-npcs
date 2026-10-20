package dev.livelynpcs.npc;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the runtime needs to know about the mobs an NPC can be: how tall they are (for the plate) and
 * where their eyes are (for looking), and the villager variants. Sizes are the 26.3 client's; a mob not
 * listed uses a villager's. Kept free of server classes so it can be tested.
 */
public final class Mobs {

    /** Villager biome types and professions, as npcs.yml's variant names them ("plains/farmer"). */
    public static final List<String> VILLAGER_TYPES = List.of("desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga");
    public static final List<String> PROFESSIONS = List.of("none", "armorer", "butcher", "cartographer", "cleric", "farmer",
            "fisherman", "fletcher", "leatherworker", "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith");

    /** Height and eye height in blocks. */
    private static final Map<String, double[]> SIZES = Map.ofEntries(
            Map.entry("player", new double[] {1.8, 1.62}),
            Map.entry("villager", new double[] {1.95, 1.62}),
            Map.entry("wandering_trader", new double[] {1.95, 1.62}),
            Map.entry("witch", new double[] {1.95, 1.62}),
            Map.entry("pillager", new double[] {1.95, 1.62}),
            Map.entry("vindicator", new double[] {1.95, 1.62}),
            Map.entry("evoker", new double[] {1.95, 1.62}),
            Map.entry("illusioner", new double[] {1.95, 1.62}),
            Map.entry("zombie", new double[] {1.95, 1.74}),
            Map.entry("husk", new double[] {1.95, 1.74}),
            Map.entry("drowned", new double[] {1.95, 1.74}),
            Map.entry("zombie_villager", new double[] {1.95, 1.74}),
            Map.entry("skeleton", new double[] {1.99, 1.74}),
            Map.entry("stray", new double[] {1.99, 1.74}),
            Map.entry("bogged", new double[] {1.99, 1.74}),
            Map.entry("wither_skeleton", new double[] {2.4, 2.1}),
            Map.entry("piglin", new double[] {1.95, 1.79}),
            Map.entry("piglin_brute", new double[] {1.95, 1.79}),
            Map.entry("zombified_piglin", new double[] {1.95, 1.79}),
            Map.entry("iron_golem", new double[] {2.7, 2.295}),
            Map.entry("snow_golem", new double[] {1.9, 1.7}),
            Map.entry("copper_golem", new double[] {0.98, 0.8125}),
            Map.entry("enderman", new double[] {2.9, 2.55}),
            Map.entry("creeper", new double[] {1.7, 1.445}),
            Map.entry("blaze", new double[] {1.8, 1.53}),
            Map.entry("allay", new double[] {0.6, 0.36}),
            Map.entry("cow", new double[] {1.4, 1.3}),
            Map.entry("mooshroom", new double[] {1.4, 1.3}),
            Map.entry("pig", new double[] {0.9, 0.765}),
            Map.entry("sheep", new double[] {1.3, 1.235}),
            Map.entry("goat", new double[] {1.3, 1.105}),
            Map.entry("chicken", new double[] {0.7, 0.644}),
            Map.entry("cat", new double[] {0.7, 0.35}),
            Map.entry("ocelot", new double[] {0.7, 0.595}),
            Map.entry("wolf", new double[] {0.85, 0.68}),
            Map.entry("fox", new double[] {0.7, 0.4}),
            Map.entry("rabbit", new double[] {0.5, 0.425}),
            Map.entry("parrot", new double[] {0.9, 0.54}),
            Map.entry("bee", new double[] {0.6, 0.3}),
            Map.entry("frog", new double[] {0.5, 0.425}),
            Map.entry("axolotl", new double[] {0.42, 0.2751}),
            Map.entry("armadillo", new double[] {0.65, 0.26}),
            Map.entry("turtle", new double[] {0.4, 0.34}),
            Map.entry("panda", new double[] {1.25, 1.0625}),
            Map.entry("polar_bear", new double[] {1.4, 1.19}),
            Map.entry("horse", new double[] {1.6, 1.52}),
            Map.entry("donkey", new double[] {1.5, 1.425}),
            Map.entry("mule", new double[] {1.6, 1.52}),
            Map.entry("llama", new double[] {1.87, 1.7765}),
            Map.entry("trader_llama", new double[] {1.87, 1.7765}),
            Map.entry("camel", new double[] {2.375, 2.275}),
            Map.entry("sniffer", new double[] {1.75, 1.05}));
    private static final double[] DEFAULT_SIZE = {1.95, 1.62};

    private Mobs() {
    }

    /** {height, eye height} in blocks of this entity ("player" or a mob). */
    public static double[] size(String entity) {
        return SIZES.getOrDefault(entity, DEFAULT_SIZE).clone();
    }

    /**
     * A villager's variant, "type/profession" ("plains/farmer"), "type", or "" (a plains villager without
     * a job), as {type, profession}. Throws IllegalArgumentException with a message for people.
     */
    public static String[] villager(String variant) {
        String v = variant == null ? "" : variant.trim().toLowerCase(Locale.ROOT);
        String type = "plains", job = "none";
        if (!v.isEmpty()) {
            int slash = v.indexOf('/');
            String a = slash < 0 ? v : v.substring(0, slash), b = slash < 0 ? "" : v.substring(slash + 1);
            if (slash < 0 && !VILLAGER_TYPES.contains(a) && PROFESSIONS.contains(a)) {
                job = a;                                       // just a profession: a plains villager with that job
            } else {
                if (!a.isEmpty()) type = a;
                if (!b.isEmpty()) job = b;
            }
        }
        if (!VILLAGER_TYPES.contains(type)) {
            throw new IllegalArgumentException("'" + type + "' is not a villager type (" + String.join(", ", VILLAGER_TYPES) + ")");
        }
        if (!PROFESSIONS.contains(job)) {
            throw new IllegalArgumentException("'" + job + "' is not a villager profession (" + String.join(", ", PROFESSIONS) + ")");
        }
        return new String[] {type, job};
    }
}
