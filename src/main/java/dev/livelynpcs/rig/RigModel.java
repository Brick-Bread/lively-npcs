package dev.livelynpcs.rig;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import dev.livelynpcs.pack.PackService;
import me.tofaa.entitylib.meta.display.ItemDisplayMeta;
import me.tofaa.entitylib.meta.other.InteractionMeta;
import me.tofaa.entitylib.wrapper.WrapperEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One NPC drawn as a model (rig), for players whose game has its skin's models: six item displays
 * showing the pack's livelynpcs:&lt;skin key&gt;/&lt;part&gt; models, the item it holds in its left hand (if
 * any), and an invisible interaction box that takes the clicks, as a display cannot be clicked. All
 * are packets, spawned at the NPC's feet (yaw 0, pitch 0, billboard fixed) in its rest pose. How the
 * model moves for each viewer is Motion's; this sends its keyframes, each a small entity-data packet
 * only that viewer receives, so everyone sees the NPC looking at them.
 *
 * <p>The body parts are item displays of type NONE with the right rotation (0, 1, 0, 0), which undoes
 * the 26.3 client's own half turn of item models; the held item is THIRD_PERSON_LEFT_HAND with no right
 * rotation, which draws it exactly as a player holds an off-hand item. Keyframes change only the glide
 * (slots 8 and 9), translation (11), left rotation (13) and, for the body, scale (12).
 */
public final class RigModel {

    /**
     * Entity-data slots in 26.3 (Display): the glide's start delay, its length, translation, scale and
     * left rotation. A wrong slot or type disconnects the client; these are the slots EntityLib 3.3.7's
     * display setters write too. The delay must go in every keyframe or the client does not restart its glide.
     */
    private static final int GLIDE_DELAY = 8, GLIDE_TICKS = 9, TRANSLATION = 11, SCALE = 12, LEFT_ROTATION = 13;
    /** The click box and the shadow, in pixels: a vanilla player's 0.6 by 1.8 block box and 0.5 shadow, a little roomier. */
    private static final float HIT_WIDTH = 12, HIT_HEIGHT = 32.5f, SHADOW = 8.5f;

    private final Motion motion;
    private final WrapperEntity[] parts = new WrapperEntity[Part.values().length];
    private final WrapperEntity hitbox;
    private final List<WrapperEntity> entities = new ArrayList<>();
    private final float scale;

    /**
     * Draws the model of an NPC at x/y/z facing yaw, wearing skinKey's models (a valid resource path: the
     * client drops the connection over a bad item model id), holding held (null: nothing) in its left hand.
     */
    public RigModel(String skinKey, ItemStack held, double x, double y, double z, float yaw, float scale, boolean slim,
                    boolean canLook, int breathTicks, int keyTicks, int stepTicks, Random random) {
        this.scale = scale;
        motion = new Motion(x, y + RigPose.EYES * scale / 16, z, yaw, scale, slim, canLook, held != null, breathTicks, keyTicks,
                stepTicks, random);
        Location feet = new Location(x, y, z, 0f, 0f);
        for (Part p : motion.parts()) {
            float[] rest = motion.rest(p);
            WrapperEntity part = new WrapperEntity(EntityTypes.ITEM_DISPLAY);
            ItemDisplayMeta meta = (ItemDisplayMeta) part.getEntityMeta();
            if (p == Part.HELD) {
                meta.setItem(held);
                meta.setDisplayType(ItemDisplayMeta.DisplayType.THIRD_PERSON_LEFT_HAND);
            } else {
                meta.setItem(ItemStack.builder().type(ItemTypes.PAPER).amount(1).component(ComponentTypes.ITEM_MODEL,
                        new ItemModel(new ResourceLocation(PackService.itemModel(skinKey, p.model)))).build());
                meta.setDisplayType(ItemDisplayMeta.DisplayType.NONE);
            }
            meta.setTranslation(new Vector3f(rest[0], rest[1], rest[2]));
            meta.setLeftRotation(new Quaternion4f(rest[3], rest[4], rest[5], rest[6]));
            meta.setScale(new Vector3f(scale, rest[7], scale));
            float[] right = RigPose.rightRotation(p);
            meta.setRightRotation(new Quaternion4f(right[0], right[1], right[2], right[3]));
            meta.setTransformationInterpolationDuration(keyTicks + 1);
            if (p == Part.BODY) {
                // A display casts no shadow of its own; the body casts the player's, at the feet.
                meta.setShadowRadius(SHADOW * scale / 16);
                meta.setShadowStrength(1f);
            }
            part.spawn(feet);
            parts[p.ordinal()] = part;
            entities.add(part);
        }
        hitbox = new WrapperEntity(EntityTypes.INTERACTION);
        InteractionMeta hm = (InteractionMeta) hitbox.getEntityMeta();
        hm.setWidth(HIT_WIDTH * scale / 16);
        hm.setHeight(HIT_HEIGHT * scale / 16);
        hm.setResponsive(true);
        hitbox.spawn(feet);
        entities.add(hitbox);
    }

    /** The displays and the click box: what a viewer of this model is sent. */
    public List<WrapperEntity> entities() {
        return entities;
    }

    /** The click box's entity id. */
    public int hitboxId() {
        return hitbox.getEntityId();
    }

    public Motion motion() {
        return motion;
    }

    /** How tall the model stands, in blocks. */
    public double height() {
        return motion.height();
    }

    /**
     * One step for one viewer (Motion.step), writing their keyframes without flushing: the caller
     * flushes once for all of a viewer's NPCs. Returns whether anything was written.
     */
    public boolean step(User user, Motion.Watch watch, int now, double ex, double ey, double ez, double lookRadius, double animateRadius) {
        return motion.step(watch, now, ex, ey, ez, lookRadius, animateRadius, (part, t, glide) -> write(user, part, t, glide));
    }

    /** Writes one part's keyframe: glide (starting now) to transformation t over glide ticks. */
    private void write(User user, Part part, float[] t, int glide) {
        List<EntityData<?>> data = new ArrayList<>(5);
        data.add(new EntityData<>(GLIDE_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(GLIDE_TICKS, EntityDataTypes.INT, glide));
        data.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f(t[0], t[1], t[2])));
        if (part == Part.BODY) data.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(scale, t[7], scale)));
        data.add(new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION, new Quaternion4f(t[3], t[4], t[5], t[6])));
        user.writePacket(new WrapperPlayServerEntityMetadata(parts[part.ordinal()].getEntityId(), data));
    }
}
