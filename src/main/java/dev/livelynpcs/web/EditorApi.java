package dev.livelynpcs.web;

import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.anim.AnimationService;
import dev.livelynpcs.npc.NpcService;
import dev.livelynpcs.pack.PackService;
import dev.livelynpcs.skin.SkinService;

/** The web editor's JSON API under /api/ (and its page under /editor/). OWNER: the editor agent. Stub until built. */
public final class EditorApi {

    private final LivelyNpcs plugin;

    public EditorApi(LivelyNpcs plugin, WebServer web, NpcService npcs, AnimationService animations, SkinService skins,
                     PackService pack) {
        this.plugin = plugin;
    }

    /** Registers /api/ on the web server; called once on enable when the web server runs. */
    public void register() { }
}
