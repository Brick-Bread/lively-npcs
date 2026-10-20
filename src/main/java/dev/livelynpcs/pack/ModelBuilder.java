package dev.livelynpcs.pack;

import dev.livelynpcs.rig.Part;
import dev.livelynpcs.skin.SkinImage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The body-part models of one skin, a port of RaiseAMob's resourcepack/npc_models.py (part_model and
 * check), in the livelynpcs namespace: item model livelynpcs:&lt;key&gt;/&lt;part&gt; names model
 * livelynpcs:npc/&lt;key&gt;/&lt;part&gt;, textured with livelynpcs:livelynpcs/&lt;key&gt;.
 *
 * <ul>
 * <li>Units are model pixels (16 = one block at display scale 1). Every part has its joint at model
 *     point (8, 8, 8), which an item display (display type none) draws at the entity's position. The
 *     front faces south (+z) and the player's right is -x.</li>
 * <li>head: 8x8x8 standing on the neck joint. body: 8x12x4 hanging below it. Arms: 4x12x4 (3x12x4 for
 *     slim skins) hanging below the shoulder joint, which is the arm's top centre. Legs: 4x12x4 below
 *     the hip joint.</li>
 * <li>Each part has a second box for the skin's outer layer (hat 0.5 px bigger on every side; jacket,
 *     sleeves and trousers 0.25 px), as on the game's player model; empty outer pixels stay see-through.</li>
 * <li>Faces take their pictures from the standard 64x64 skin layout the way the player model does,
 *     which {@link #check} proves by redrawing every base face from the model's uv, pixel for pixel.</li>
 * </ul>
 * JDK only (ExportCli uses it).
 */
public final class ModelBuilder {

    public static final String NAMESPACE = "livelynpcs";

    /**
     * A part: its box (w, h, d) in skin pixels, where its base (u, v) and outer layer's (ou, ov) pictures
     * start in the skin, and how much bigger the outer layer's box is.
     */
    record Shape(int w, int h, int d, int u, int v, int ou, int ov, double grow) {
    }

    /** npc_models.py's PARTS, in the same order. */
    static final Map<String, Shape> PARTS = new LinkedHashMap<>();

    static {
        PARTS.put("head", new Shape(8, 8, 8, 0, 0, 32, 0, 0.5));
        PARTS.put("body", new Shape(8, 12, 4, 16, 16, 16, 32, 0.25));
        PARTS.put("right_arm", new Shape(4, 12, 4, 40, 16, 40, 32, 0.25));
        PARTS.put("left_arm", new Shape(4, 12, 4, 32, 48, 48, 48, 0.25));
        PARTS.put("right_leg", new Shape(4, 12, 4, 0, 16, 0, 32, 0.25));
        PARTS.put("left_leg", new Shape(4, 12, 4, 16, 48, 0, 48, 0.25));
    }

    /** The six faces, in npc_models.py's order. */
    static final List<String> SIDES = List.of("north", "east", "south", "west", "up", "down");

    /** The part names, as the pack's model ids use them (the same as rig.Part's). */
    public static List<String> parts() {
        List<String> names = new ArrayList<>();
        for (Part part : Part.BODY_PARTS) names.add(part.model);
        return names;
    }

    private ModelBuilder() {
    }

    /** The item model id of a skin's part: "livelynpcs:&lt;key&gt;/&lt;part&gt;" (as PackService.itemModel). */
    public static String itemModel(String key, String part) {
        return NAMESPACE + ":" + key + "/" + part;
    }

    /** The model id: "livelynpcs:npc/&lt;key&gt;/&lt;part&gt;". */
    public static String model(String key, String part) {
        return NAMESPACE + ":npc/" + key + "/" + part;
    }

    /** The texture id: "livelynpcs:livelynpcs/&lt;key&gt;". */
    public static String texture(String key) {
        return NAMESPACE + ":" + NAMESPACE + "/" + key;
    }

    /** PARTS[part], with the 3-wide arms of a slim skin. */
    static Shape shape(String part, boolean slim) {
        Shape s = PARTS.get(part);
        if (s == null) throw new IllegalArgumentException("no part " + part);
        if (slim && part.endsWith("_arm")) return new Shape(3, s.h(), s.d(), s.u(), s.v(), s.ou(), s.ov(), s.grow());
        return s;
    }

    /**
     * Where each face of a w x h x d box with its picture at (u, v) finds it in the skin, as {left, top,
     * right, bottom} of the face seen from outside, upright; the top and bottom are seen with north up.
     * This is the game's player model: south is the front, west the player's right side.
     */
    static Map<String, int[]> regions(int u, int v, int w, int h, int d) {
        Map<String, int[]> r = new LinkedHashMap<>();
        r.put("north", new int[]{u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h});
        r.put("east", new int[]{u + d + w, v + d, u + 2 * d + w, v + d + h});
        r.put("south", new int[]{u + d, v + d, u + d + w, v + d + h});
        r.put("west", new int[]{u, v + d, u + d, v + d + h});
        r.put("up", new int[]{u + d, v, u + d + w, v + d});
        r.put("down", new int[]{u + d + w, v, u + d + 2 * w, v + d});
        return r;
    }

    /**
     * The part's model: its base box around the joint, then the outer layer's box, with every face's uv
     * (0..16 across the whole 64-pixel skin, so a skin pixel p is p/4).
     */
    public static Map<String, Object> partModel(String key, String part, boolean slim) {
        Shape s = shape(part, slim);
        double y0 = part.equals("head") ? 8 : 8 - s.h();
        double y1 = part.equals("head") ? 8 + s.h() : 8;
        double[] lo = {8 - s.w() / 2.0, y0, 8 - s.d() / 2.0};
        double[] hi = {8 + s.w() / 2.0, y1, 8 + s.d() / 2.0};
        List<Object> elements = new ArrayList<>();
        int[][] pictures = {{s.u(), s.v()}, {s.ou(), s.ov()}};
        double[] grows = {0, s.grow()};
        for (int layer = 0; layer < 2; layer++) {
            Map<String, Object> faces = new LinkedHashMap<>();
            for (Map.Entry<String, int[]> e : regions(pictures[layer][0], pictures[layer][1], s.w(), s.h(), s.d()).entrySet()) {
                int[] q = e.getValue(); // left, top, right, bottom
                // The game reads a face's v from its top edge, except the bottom face's, from its south edge.
                int[] uv = e.getKey().equals("down") ? new int[]{q[0], q[3], q[2], q[1]} : q;
                List<Object> scaled = new ArrayList<>();
                for (int p : uv) scaled.add(p / 4.0);
                Map<String, Object> face = new LinkedHashMap<>();
                face.put("uv", scaled);
                face.put("texture", "#skin");
                faces.put(e.getKey(), face);
            }
            double g = grows[layer];
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("from", List.of(lo[0] - g, lo[1] - g, lo[2] - g));
            element.put("to", List.of(hi[0] + g, hi[1] + g, hi[2] + g));
            element.put("faces", faces);
            elements.add(element);
        }
        Map<String, Object> textures = new LinkedHashMap<>();
        textures.put("skin", texture(key));
        textures.put("particle", "#skin");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("ambientocclusion", false);
        model.put("textures", textures);
        model.put("elements", elements);
        return model;
    }

    /** The item definition: {"model": {"type": "minecraft:model", "model": "livelynpcs:npc/&lt;key&gt;/&lt;part&gt;"}}. */
    public static Map<String, Object> itemDefinition(String key, String part) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("type", "minecraft:model");
        inner.put("model", model(key, part));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("model", inner);
        return item;
    }

    // ---- checking the models the way the game reads them ----

    /**
     * The corners of each face in the order the game gives them uv (u1, v1), (u1, v2), (u2, v2), (u2, v1)
     * (FaceInfo); per axis x, y, z: 0 = the element's from, 1 = its to.
     */
    static final Map<String, int[][]> CORNERS = Map.of(
            "down", new int[][]{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
            "up", new int[][]{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            "north", new int[][]{{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},
            "south", new int[][]{{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},
            "west", new int[][]{{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},
            "east", new int[][]{{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}});
    /** How a face is looked at, as the picture's right and down in model space, like regions(). */
    static final Map<String, int[][]> VIEWS = Map.of(
            "north", new int[][]{{-1, 0, 0}, {0, -1, 0}},
            "east", new int[][]{{0, 0, -1}, {0, -1, 0}},
            "south", new int[][]{{1, 0, 0}, {0, -1, 0}},
            "west", new int[][]{{0, 0, 1}, {0, -1, 0}},
            "up", new int[][]{{1, 0, 0}, {0, 0, 1}},
            "down", new int[][]{{1, 0, 0}, {0, 0, 1}});

    /**
     * One face of an element textured the way the game does it, seen as VIEWS says, scale pixels per
     * model pixel (npc_models.py's face_picture).
     */
    static SkinImage.Pixels facePicture(Map<String, Object> element, String side, SkinImage.Pixels skin, int scale) {
        double[] from = numbers(element.get("from"), 3);
        double[] to = numbers(element.get("to"), 3);
        int[][] corners = CORNERS.get(side);
        int[] right = VIEWS.get(side)[0], down = VIEWS.get(side)[1];
        double[][] points = new double[4][2];
        for (int k = 0; k < 4; k++) {
            double[] c = new double[3];
            for (int axis = 0; axis < 3; axis++) c[axis] = corners[k][axis] == 0 ? from[axis] : to[axis];
            points[k][0] = dot(c, right);
            points[k][1] = dot(c, down);
        }
        double ax = points[0][0], ay = points[0][1], bx = points[1][0], by = points[1][1];
        double dx = points[3][0], dy = points[3][1];
        double left = Double.MAX_VALUE, top = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : points) {
            left = Math.min(left, p[0]);
            top = Math.min(top, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        int width = (int) Math.round((maxX - left) * scale);
        int height = (int) Math.round((maxY - top) * scale);
        double[] uv = numbers(at(element, "faces", side, "uv"), 4);
        double u1 = uv[0], v1 = uv[1], u2 = uv[2], v2 = uv[3];
        SkinImage.Pixels picture = new SkinImage.Pixels(width, height, new int[width * height]);
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                double px = left + (i + 0.5) / scale, py = top + (j + 0.5) / scale;
                double s = ((px - ax) * (dx - ax) + (py - ay) * (dy - ay)) / ((dx - ax) * (dx - ax) + (dy - ay) * (dy - ay));
                double t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / ((bx - ax) * (bx - ax) + (by - ay) * (by - ay));
                double u = u1 + s * (u2 - u1), v = v1 + t * (v2 - v1);
                picture.set(i, j, skin.get((int) (u * 4), (int) (v * 4)));
            }
        }
        return picture;
    }

    private static double dot(double[] a, int[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * Throws IllegalStateException if a skin's models are wrong: the skin must be 64x64, each part's
     * item must name its model, the model must be textured with the skin, coordinates must be within the
     * game's -16..32 and uv within 0..16, and the base layer's faces, drawn the way the game maps uv,
     * must show exactly their regions of the skin, with the outer layer mapped the same way
     * (npc_models.py's check).
     */
    public static void check(String key, boolean slim, SkinImage.Pixels skin, Map<String, Map<String, Object>> models,
                             Map<String, Map<String, Object>> items) {
        if (skin.width() != 64 || skin.height() != 64) {
            throw new IllegalStateException("skin " + key + " is " + skin.width() + "x" + skin.height() + ", not 64x64");
        }
        for (String part : PARTS.keySet()) {
            String name = model(key, part);
            Map<String, Object> item = items.get(part);
            if (item == null || !item.equals(itemDefinition(key, part))) {
                throw new IllegalStateException(name + ": the item definition does not name its model");
            }
            Map<String, Object> model = models.get(part);
            if (model == null) throw new IllegalStateException(name + ": no model");
            if (!texture(key).equals(at(model, "textures", "skin"))) {
                throw new IllegalStateException(name + ": not textured with " + texture(key));
            }
            if (!(model.get("elements") instanceof List<?> elements) || elements.size() != 2) {
                throw new IllegalStateException(name + ": needs exactly a base and an outer element");
            }
            @SuppressWarnings("unchecked") Map<String, Object> base = (Map<String, Object>) elements.get(0);
            @SuppressWarnings("unchecked") Map<String, Object> outer = (Map<String, Object>) elements.get(1);
            for (Map<String, Object> element : List.of(base, outer)) {
                for (String end : List.of("from", "to")) {
                    for (double c : numbers(element.get(end), 3)) {
                        if (c < -16 || c > 32) throw new IllegalStateException(name + ": an element is outside -16..32");
                    }
                }
                for (String side : SIDES) {
                    for (double p : numbers(at(element, "faces", side, "uv"), 4)) {
                        if (p < 0 || p > 16) throw new IllegalStateException(name + ": a uv is outside 0..16");
                    }
                }
            }
            Shape s = shape(part, slim);
            for (Map.Entry<String, int[]> e : regions(s.u(), s.v(), s.w(), s.h(), s.d()).entrySet()) {
                String side = e.getKey();
                int[] q = e.getValue();
                SkinImage.Pixels drawn = facePicture(base, side, skin, 1);
                int w = q[2] - q[0], h = q[3] - q[1];
                boolean same = drawn.width() == w && drawn.height() == h;
                for (int y = 0; same && y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (drawn.get(x, y) != skin.get(q[0] + x, q[1] + y)) {
                            same = false;
                            break;
                        }
                    }
                }
                if (!same) {
                    throw new IllegalStateException(name + ": the " + side + " face does not show skin (" + q[0] + ", " + q[1]
                            + ", " + q[2] + ", " + q[3] + ")");
                }
                double du = (s.ou() - s.u()) / 4.0, dv = (s.ov() - s.v()) / 4.0;
                double[] b = numbers(at(base, "faces", side, "uv"), 4);
                double[] o = numbers(at(outer, "faces", side, "uv"), 4);
                if (o[0] != b[0] + du || o[1] != b[1] + dv || o[2] != b[2] + du || o[3] != b[3] + dv) {
                    throw new IllegalStateException(name + ": the outer layer's " + side + " face is not mapped like the base");
                }
            }
        }
    }

    private static Object at(Object value, String... path) {
        Object v = value;
        for (String key : path) {
            if (!(v instanceof Map<?, ?> map)) return null;
            v = map.get(key);
        }
        return v;
    }

    private static double[] numbers(Object list, int count) {
        if (!(list instanceof List<?> l) || l.size() != count) throw new IllegalStateException("expected " + count + " numbers, got " + list);
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            if (!(l.get(i) instanceof Number n)) throw new IllegalStateException("expected numbers, got " + list);
            out[i] = n.doubleValue();
        }
        return out;
    }
}
