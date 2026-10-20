package dev.livelynpcs.anim;

import java.util.Arrays;

/**
 * A moment of an animation: at this tick (20 a second, from the start) every channel has these
 * values (0 where a file leaves one out), reached from the keyframe before with this easing.
 */
public record Keyframe(int at, Easing ease, float[] values) {

    public Keyframe {
        if (at < 0) throw new IllegalArgumentException("a keyframe cannot be before the start (at " + at + ")");
        if (ease == null) ease = Easing.LINEAR;
        values = values == null ? new float[Channel.COUNT] : Arrays.copyOf(values, Channel.COUNT);
    }

    public float value(Channel channel) {
        return values[channel.ordinal()];
    }

    /** A copy of the values (the record's own array is never handed out to be changed). */
    @Override
    public float[] values() {
        return values.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Keyframe k && k.at == at && k.ease == ease && Arrays.equals(k.values, values);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * at + ease.hashCode()) + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Keyframe[at=" + at + ", ease=" + ease.key + ", values=" + Arrays.toString(values) + "]";
    }
}
