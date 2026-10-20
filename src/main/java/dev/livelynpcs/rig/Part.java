package dev.livelynpcs.rig;

/**
 * The parts of an NPC model (rig): six item displays drawing the pack's body-part models, plus the
 * item held in the left hand (an item display of the item itself). {@link #model} is the part's name
 * in the pack's model ids (livelynpcs:&lt;skin key&gt;/&lt;model&gt;); HELD has none.
 */
public enum Part {
    HEAD("head"),
    BODY("body"),
    RIGHT_ARM("right_arm"),
    LEFT_ARM("left_arm"),
    RIGHT_LEG("right_leg"),
    LEFT_LEG("left_leg"),
    HELD(null);

    /** The six parts the pack has a model for, in order. */
    public static final Part[] BODY_PARTS = {HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG};

    public final String model;

    Part(String model) {
        this.model = model;
    }
}
