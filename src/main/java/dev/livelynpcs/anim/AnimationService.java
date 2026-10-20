package dev.livelynpcs.anim;

import java.io.IOException;
import java.util.Collection;

/**
 * The animations: the built-in presets (wave, talk, beckon, ...) and the server's own, in
 * plugins/LivelyNPCs/animations/&lt;id&gt;.yml. A saved animation with a preset's id replaces that
 * preset on this server; deleting it brings the preset back. Main thread only.
 */
public interface AnimationService {

    /** Every animation, presets (or their saved replacements) included, sorted by id. */
    Collection<Animation> all();

    /** The animation with this id, or null. */
    Animation get(String id);

    /** The preset as the plugin ships it, or null if id is not a preset. */
    Animation preset(String id);

    /** Saves (creates or replaces) an animation to its file; listeners hear of it. */
    void save(Animation animation) throws IOException;

    /** Deletes the server's animation (a preset goes back to how it ships). False if there was none. */
    boolean delete(String id) throws IOException;

    /** Re-reads the folder (after /npc reload). Problems are logged with their file. */
    void reload();

    /** Called on the main thread after any save, delete or reload. */
    void addListener(Runnable onChange);
}
