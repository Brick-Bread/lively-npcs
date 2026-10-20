package dev.livelynpcs.anim;

import java.util.Locale;

/** How a value moves into a keyframe from the one before: the share of the way along at share f of the time. */
public enum Easing {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, STEP;

    /** The file/JSON name, e.g. "ease-in-out". */
    public final String key = name().toLowerCase(Locale.ROOT).replace('_', '-');

    public double apply(double f) {
        f = Math.max(0, Math.min(1, f));
        return switch (this) {
            case LINEAR -> f;
            case EASE_IN -> f * f;
            case EASE_OUT -> 1 - (1 - f) * (1 - f);
            case EASE_IN_OUT -> f < 0.5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2;
            case STEP -> f >= 1 ? 1 : 0;
        };
    }

    /** The easing with this file/JSON key, or null. */
    public static Easing byKey(String key) {
        for (Easing e : values()) if (e.key.equals(key)) return e;
        return null;
    }
}
