package dev.livelynpcs.skin;

/**
 * A skin could not be had (no such player, no MineSkin key, not a skin image, Mojang or MineSkin busy
 * or down, ...). The message is for people: it is shown as it is in chat and in the web editor.
 */
public final class SkinException extends Exception {

    public SkinException(String message) {
        super(message);
    }

    public SkinException(String message, Throwable cause) {
        super(message, cause);
    }
}
