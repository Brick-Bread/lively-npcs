package dev.livelynpcs.anim;

import dev.livelynpcs.rig.Part;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An animation: keyframes on a timeline (ticks, 20 a second). The file format (animations/&lt;id&gt;.yml)
 * and the editor's JSON are the same map, see {@link #toMap()} and DESIGN.md:
 *
 * <pre>
 * name: Wave          # shown in the editor
 * length: 44          # ticks; at least the last keyframe's tick
 * loop: false         # true: plays round and round (a preview, or played by command until stopped)
 * glance: false       # true: only for players the NPC is not looking at (it turns the head away)
 * keyframes:
 *   - at: 0                     # the first keyframe is always at 0 (one is added at rest if missing)
 *   - at: 4
 *     ease: ease-out            # into this keyframe: linear (default), ease-in, ease-out, ease-in-out, step
 *     right-arm-forward: 90     # any Channel key; a channel left out is 0 (its rest)
 *     root-y: 3.5
 * </pre>
 *
 * <p>A one-shot animation should end at rest (every channel 0) so the NPC finishes in its own pose;
 * the editor warns when it does not. Values between keyframes come from {@link #sample}.
 */
public record Animation(String id, String name, int length, boolean loop, boolean glance, List<Keyframe> keyframes,
                        boolean builtin) {

    /** What an animation id may hold: it names a file and goes in commands. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,40}");

    public Animation {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("'" + id + "' is not an animation id (a-z, 0-9, _ and -)");
        if (name == null || name.isBlank()) name = id;
        List<Keyframe> sorted = new ArrayList<>(keyframes == null ? List.of() : keyframes);
        sorted.sort(Comparator.comparingInt(Keyframe::at));
        if (sorted.isEmpty() || sorted.getFirst().at() != 0) sorted.addFirst(new Keyframe(0, Easing.LINEAR, null));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).at() == sorted.get(i - 1).at()) {
                throw new IllegalArgumentException("two keyframes at tick " + sorted.get(i).at());
            }
        }
        keyframes = List.copyOf(sorted);
        length = Math.max(Math.max(1, length), keyframes.getLast().at());
    }

    /**
     * Every channel's value at this tick (fractions allowed) into out (Channel.COUNT long): between
     * two keyframes the later one's easing decides the way; past the last keyframe a looping animation
     * glides back to the first by length, a one-shot one holds the last. Before 0 is the first.
     */
    public void sample(double tick, float[] out) {
        List<Keyframe> k = keyframes;
        if (loop) tick = ((tick % length) + length) % length;
        if (tick <= 0) {
            copy(k.getFirst(), out);
            return;
        }
        int next = 0;
        while (next < k.size() && k.get(next).at() <= tick) next++;
        Keyframe a = k.get(next - 1);
        Keyframe b;
        double end;
        if (next < k.size()) {
            b = k.get(next);
            end = b.at();
        } else if (loop && length > a.at()) {
            b = k.getFirst();
            end = length;
        } else {
            copy(a, out);
            return;
        }
        double e = b.ease().apply((tick - a.at()) / (end - a.at()));
        for (Channel c : Channel.ALL) {
            float from = a.value(c), to = b.value(c);
            out[c.ordinal()] = (float) (from + (to - from) * e);
        }
    }

    private static void copy(Keyframe k, float[] out) {
        for (Channel c : Channel.ALL) out[c.ordinal()] = k.value(c);
    }

    /** Whether any keyframe moves this part (a channel that moves it is not 0 somewhere). */
    public boolean moves(Part part) {
        for (Keyframe k : keyframes) {
            for (Channel c : Channel.ALL) if (c.moves(part) && k.value(c) != 0) return true;
        }
        return false;
    }

    /** Whether it ends at rest (every channel 0 at the last keyframe). */
    public boolean endsAtRest() {
        for (Channel c : Channel.ALL) if (keyframes.getLast().value(c) != 0) return false;
        return true;
    }

    /** The same animation under another id (a copy of a preset, say), not built in. */
    public Animation withId(String newId, String newName) {
        return new Animation(newId, newName, length, loop, glance, keyframes, false);
    }

    /** As a file or the editor's JSON: name, length, loop, glance, keyframes (only channels that are not 0). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("length", length);
        m.put("loop", loop);
        m.put("glance", glance);
        List<Map<String, Object>> keys = new ArrayList<>();
        for (Keyframe k : keyframes) {
            Map<String, Object> km = new LinkedHashMap<>();
            km.put("at", k.at());
            if (k.ease() != Easing.LINEAR) km.put("ease", k.ease().key);
            for (Channel c : Channel.ALL) {
                float v = k.value(c);
                if (v != 0) km.put(c.key, Math.round(v * 1000) / 1000.0);
            }
            keys.add(km);
        }
        m.put("keyframes", keys);
        return m;
    }

    /**
     * Reads the map {@link #toMap()} writes (from YAML or JSON). Unknown keys, a bad ease or a value
     * that is not a number are added to problems ("where: what") and skipped; a broken shape (no
     * keyframes list, a keyframe without at) throws IllegalArgumentException.
     */
    public static Animation fromMap(String id, Map<?, ?> m, boolean builtin, List<String> problems) {
        String name = m.get("name") instanceof String s ? s : id;
        int length = m.get("length") instanceof Number n ? n.intValue() : 0;
        boolean loop = Boolean.TRUE.equals(m.get("loop"));
        boolean glance = Boolean.TRUE.equals(m.get("glance"));
        if (!(m.get("keyframes") instanceof List<?> list)) throw new IllegalArgumentException(id + ": no keyframes list");
        List<Keyframe> keys = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> km)) throw new IllegalArgumentException(id + ": keyframe " + (i + 1) + " is not a map");
            if (!(km.get("at") instanceof Number at)) throw new IllegalArgumentException(id + ": keyframe " + (i + 1) + " has no at");
            Easing ease = Easing.LINEAR;
            float[] values = new float[Channel.COUNT];
            for (Map.Entry<?, ?> e : km.entrySet()) {
                String key = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                if (key.equals("at")) continue;
                if (key.equals("ease")) {
                    Easing found = Easing.byKey(String.valueOf(e.getValue()).toLowerCase(Locale.ROOT));
                    if (found == null) problems.add(id + " keyframe " + (i + 1) + ": no ease called '" + e.getValue() + "'");
                    else ease = found;
                    continue;
                }
                Channel c = Channel.byKey(key);
                if (c == null) {
                    problems.add(id + " keyframe " + (i + 1) + ": no channel called '" + key + "'");
                } else if (e.getValue() instanceof Number v && Double.isFinite(v.doubleValue())) {
                    values[c.ordinal()] = v.floatValue();
                } else {
                    problems.add(id + " keyframe " + (i + 1) + ": " + key + " is not a number");
                }
            }
            keys.add(new Keyframe(at.intValue(), ease, values));
        }
        return new Animation(id, name, length, loop, glance, keys, builtin);
    }
}
