package dev.livelynpcs.npc;

import dev.livelynpcs.rig.Motion;
import dev.livelynpcs.rig.RigModel;
import dev.livelynpcs.skin.Skin;
import me.tofaa.entitylib.wrapper.WrapperEntity;
import me.tofaa.entitylib.wrapper.WrapperLivingEntity;
import org.bukkit.Chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One NPC as drawn: its body (a player model or a mob), its model (rig) for players whose game has the
 * skin's models, its plate and icon, and what each viewer has been sent of it. Everything is packets.
 */
final class Drawn {

    /** What one viewer has of this NPC. */
    static final class Seen {
        /** They see the model (rig) rather than the body. */
        boolean rigged;
        /** The model as they see it (rigged only). */
        Motion.Watch watch;
        /** The head angles their client last got (player models and mobs). */
        float yaw, pitch;
        /** Inside greet-radius at the last step; known: a step has seen where they stand (no greeting on joining). */
        boolean near, known;
        /** The player model's player-list entry (which carries its skin) is still on their client. */
        boolean listed;
        /** Ticks the NPC's chunk has been on their client while listed. */
        int sent;
        /** A mob nods for them (a greeting or a click) until this tick. */
        int nodUntil = Integer.MIN_VALUE;
        /** When this NPC last beckoned them. */
        int lastBeckon = Integer.MIN_VALUE / 2;
    }

    final NpcDef def;
    /** The skin it wears, or null: the default skin, or a mob. */
    final Skin skin;
    final WrapperLivingEntity body;
    /** The body's team entry: a player model's name, a mob's UUID. */
    final String member;
    /** The model, or null (a mob, no skin, or a skin the pack cannot name). */
    final RigModel rig;
    /** Null when it has no plate or no icon. */
    final WrapperEntity plate, icon;
    /** Where its eyes are, for looking, greeting and sparkles. */
    final double eyeY;
    /** The Paper chunk key of the chunk it stands in. */
    final long chunk;
    final Map<UUID, Seen> seen = new HashMap<>();
    /** The shared idle life: when the next idle is due and which played last; a player model's swing, a mob's nod. */
    int nextIdle;
    String lastIdle = "";
    int swingAt = Integer.MIN_VALUE, nodUntil = Integer.MIN_VALUE;

    Drawn(NpcDef def, Skin skin, WrapperLivingEntity body, String member, RigModel rig, WrapperEntity plate, WrapperEntity icon,
          double eyeY) {
        this.def = def;
        this.skin = skin;
        this.body = body;
        this.member = member;
        this.rig = rig;
        this.plate = plate;
        this.icon = icon;
        this.eyeY = eyeY;
        this.chunk = Chunk.getChunkKey((int) Math.floor(def.x()) >> 4, (int) Math.floor(def.z()) >> 4);
    }

    boolean person() {
        return def.person();
    }

    /** What a viewer is sent: the plate and icon, and the model if they see it, else the body. */
    List<WrapperEntity> seenBy(boolean rigged) {
        List<WrapperEntity> out = new ArrayList<>();
        if (plate != null) out.add(plate);
        if (icon != null) out.add(icon);
        if (rigged && rig != null) out.addAll(rig.entities());
        else out.add(body);
        return out;
    }

    /** Everything it draws, for anyone. */
    List<WrapperEntity> all() {
        List<WrapperEntity> out = seenBy(false);
        if (rig != null) out.addAll(rig.entities());
        return out;
    }
}
