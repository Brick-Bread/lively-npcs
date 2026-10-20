package dev.livelynpcs.pack;

import dev.livelynpcs.rig.Part;
import dev.livelynpcs.skin.Json;
import dev.livelynpcs.skin.SkinImage;
import dev.livelynpcs.skin.TestSkins;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The body-part models redrawn from their uv, pixel for pixel, on skins whose every pixel differs. */
class ModelBuilderTest {

    private static final String KEY = "3f2a9c0d11e4b7a2";

    private static Map<String, Map<String, Object>> models(boolean slim) {
        Map<String, Map<String, Object>> models = new HashMap<>();
        for (String part : ModelBuilder.parts()) models.put(part, ModelBuilder.partModel(KEY, part, slim));
        return models;
    }

    private static Map<String, Map<String, Object>> items() {
        Map<String, Map<String, Object>> items = new HashMap<>();
        for (String part : ModelBuilder.parts()) items.put(part, ModelBuilder.itemDefinition(KEY, part));
        return items;
    }

    @Test
    void everyBaseFaceShowsItsSkinRegionOnClassicSlimAndOldSkins() {
        for (long seed = 1; seed <= 5; seed++) {
            SkinImage.Pixels classic = SkinImage.asTheGameDraws(TestSkins.unique(64, 64, seed));
            ModelBuilder.check(KEY, false, classic, models(false), items());
            ModelBuilder.check(KEY, true, classic, models(true), items());
            SkinImage.Pixels legacy = SkinImage.asTheGameDraws(TestSkins.unique(64, 32, seed));
            ModelBuilder.check(KEY, false, legacy, models(false), items());
            ModelBuilder.check(KEY, true, legacy, models(true), items());
        }
    }

    @Test
    void aWrongUvIsCaught() {
        SkinImage.Pixels skin = SkinImage.asTheGameDraws(TestSkins.unique(64, 64, 7));
        for (String side : List.of("north", "east", "south", "west", "up", "down")) {
            Map<String, Map<String, Object>> broken = models(false);
            Map<String, Object> face = face(broken.get("left_arm"), 0, side);
            @SuppressWarnings("unchecked") List<Object> uv = (List<Object>) face.get("uv");
            // Flipping the face picture (u1 and u2 swapped) must not pass.
            face.put("uv", List.of(uv.get(2), uv.get(1), uv.get(0), uv.get(3)));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> ModelBuilder.check(KEY, false, skin, broken, items()));
            assertTrue(e.getMessage().contains(side), e.getMessage());
        }
        // Slim arms on a classic model, or the other way round, do not pass either.
        assertThrows(IllegalStateException.class, () -> ModelBuilder.check(KEY, true, skin, models(false), items()));
        assertThrows(IllegalStateException.class, () -> ModelBuilder.check(KEY, false, skin, models(true), items()));
        // An outer layer mapped differently from its base.
        Map<String, Map<String, Object>> outer = models(false);
        face(outer.get("head"), 1, "up").put("uv", List.of(0.0, 0.0, 1.0, 1.0));
        assertThrows(IllegalStateException.class, () -> ModelBuilder.check(KEY, false, skin, outer, items()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> face(Map<String, Object> model, int element, String side) {
        List<Object> elements = (List<Object>) model.get("elements");
        Map<String, Object> e = (Map<String, Object>) elements.get(element);
        return (Map<String, Object>) ((Map<String, Object>) e.get("faces")).get(side);
    }

    @Test
    void theBoxesAreThePlayersAroundTheJoint() {
        // DESIGN.md: head x -4..4, y 0..8, z -4..4 (hat 0.5 bigger); arms x -2..2 (slim -1.5..1.5), y -12..0;
        // the model point (8, 8, 8) is the joint.
        Map<String, Object> head = ModelBuilder.partModel(KEY, "head", false);
        assertEquals(Json.parse("[{\"from\":[4,8,4],\"to\":[12,16,12]},{\"from\":[3.5,7.5,3.5],\"to\":[12.5,16.5,12.5]}]"),
                Json.parse(Json.write(boxes(head))));
        Map<String, Object> slimArm = ModelBuilder.partModel(KEY, "right_arm", true);
        assertEquals(Json.parse("[{\"from\":[6.5,-4,6],\"to\":[9.5,8,10]},{\"from\":[6.25,-4.25,5.75],\"to\":[9.75,8.25,10.25]}]"),
                Json.parse(Json.write(boxes(slimArm))));
        Map<String, Object> body = ModelBuilder.partModel(KEY, "body", true);
        assertEquals(Json.parse("[{\"from\":[4,-4,6],\"to\":[12,8,10]},{\"from\":[3.75,-4.25,5.75],\"to\":[12.25,8.25,10.25]}]"),
                Json.parse(Json.write(boxes(body))));
        assertEquals("livelynpcs:livelynpcs/" + KEY, ((Map<?, ?>) head.get("textures")).get("skin"));
        assertEquals(Boolean.FALSE, head.get("ambientocclusion"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> boxes(Map<String, Object> model) {
        List<Object> out = new java.util.ArrayList<>();
        for (Object e : (List<Object>) model.get("elements")) {
            Map<String, Object> element = (Map<String, Object>) e;
            out.add(Map.of("from", element.get("from"), "to", element.get("to")));
        }
        return out;
    }

    @Test
    void theBottomFaceIsReadFromItsSouthEdge() {
        // The head's bottom is skin (16, 0)-(24, 8), its uv given upside down: v from 8 to 0, i.e. 2 to 0.
        assertEquals(List.of(4.0, 2.0, 6.0, 0.0), face(ModelBuilder.partModel(KEY, "head", false), 0, "down").get("uv"));
        assertEquals(List.of(2.0, 0.0, 4.0, 2.0), face(ModelBuilder.partModel(KEY, "head", false), 0, "up").get("uv"));
    }

    @Test
    void idsMatchTheContractAndTheRig() {
        assertEquals(List.of("head", "body", "right_arm", "left_arm", "right_leg", "left_leg"), ModelBuilder.parts());
        for (Part part : Part.BODY_PARTS) {
            assertEquals(PackService.itemModel(KEY, part.model), ModelBuilder.itemModel(KEY, part.model));
        }
        assertEquals(Json.parse("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"livelynpcs:npc/" + KEY + "/left_leg\"}}"),
                Json.parse(Json.write(ModelBuilder.itemDefinition(KEY, "left_leg"))));
    }
}
