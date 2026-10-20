package dev.livelynpcs.npc;

import java.util.Locale;

/**
 * One thing a click on an NPC does (npcs.yml click:), in order: {@code player:<command>} runs a command
 * as the player, {@code console:<command>} runs one as the console, {@code message:<MiniMessage>} tells
 * the player something. {player} becomes the clicker's name. Kept free of server classes for tests.
 */
public record ClickAction(Type type, String text) {

    public enum Type {
        PLAYER, CONSOLE, MESSAGE;

        public final String prefix = name().toLowerCase(Locale.ROOT);
    }

    /** Reads "player:spawn" and the like; throws IllegalArgumentException with a message for people. */
    public static ClickAction parse(String line) {
        if (line == null) throw new IllegalArgumentException("an empty click action");
        int colon = line.indexOf(':');
        String kind = colon < 0 ? "" : line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String text = colon < 0 ? "" : line.substring(colon + 1).trim();
        for (Type type : Type.values()) {
            if (!type.prefix.equals(kind)) continue;
            if (type != Type.MESSAGE && text.startsWith("/")) text = text.substring(1);
            if (text.isEmpty()) throw new IllegalArgumentException("'" + line + "' has nothing after " + kind + ":");
            return new ClickAction(type, text);
        }
        throw new IllegalArgumentException("'" + line + "' should start with player:, console: or message:");
    }

    /** The command or message for this clicker. In a message the name is escaped so it stays plain text. */
    public String forPlayer(String name) {
        String safe = type == Type.MESSAGE ? name.replace("\\", "\\\\").replace("<", "\\<") : name;
        return text.replace("{player}", safe);
    }

    /** As npcs.yml writes it. */
    @Override
    public String toString() {
        return type.prefix + ":" + text;
    }
}
