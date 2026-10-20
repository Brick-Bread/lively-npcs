package dev.livelynpcs.anim;

import dev.livelynpcs.LivelyNpcs;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/** The animations (presets and the server's own). OWNER: the runtime agent. Stub until built. */
public final class AnimationStore implements AnimationService {

    private final LivelyNpcs plugin;

    public AnimationStore(LivelyNpcs plugin) {
        this.plugin = plugin;
    }

    @Override public Collection<Animation> all() { return List.of(); }
    @Override public Animation get(String id) { return null; }
    @Override public Animation preset(String id) { return null; }
    @Override public void save(Animation animation) throws IOException { throw new IOException("not built yet"); }
    @Override public boolean delete(String id) { return false; }
    @Override public void reload() { }
    @Override public void addListener(Runnable onChange) { }
}
