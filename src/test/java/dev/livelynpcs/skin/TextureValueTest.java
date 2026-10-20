package dev.livelynpcs.skin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The skin key and slim flag, read from a signed textures value. */
class TextureValueTest {

    private static final String TEXTURE = "49c88354a0494b6b9f2108997660ee9c06a2a5c39fd4c9ff37ceeab983240655";

    private static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void keyIsTheTexturesFirstSixteenCharacters() {
        TextureValue classic = TextureValue.parse(TestSkins.value(TEXTURE, false));
        assertEquals(TEXTURE, classic.texture());
        assertEquals("49c88354a0494b6b", classic.key());
        assertFalse(classic.slim());
        assertTrue(TextureValue.parse(TestSkins.value(TEXTURE, true)).slim());
    }

    @Test
    void textureIdsWithoutLeadingZerosAndHttpsLinksAreFine() {
        String stripped = "9ba80c7490b65203efbda941490344834a4fc07a43b363724e1d88af49359e0"; // 63 characters
        TextureValue v = TextureValue.parse(encode("{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                + stripped + "\",\"metadata\":{\"model\":\"classic\"}}}}"));
        assertEquals(stripped, v.texture());
        assertEquals("9ba80c7490b65203", v.key());
        assertFalse(v.slim(), "only model 'slim' is slim");
    }

    @Test
    void valuesSplitOverLinesStillRead() {
        String value = TestSkins.value(TEXTURE, true);
        String wrapped = value.substring(0, 40) + "\n" + value.substring(40);
        assertEquals(TEXTURE, TextureValue.parse(wrapped).texture());
    }

    @Test
    void refusesWhatIsNotASkinOnMojangsServer() {
        assertThrows(IllegalArgumentException.class, () -> TextureValue.parse(""));
        assertThrows(IllegalArgumentException.class, () -> TextureValue.parse("not base64 !!"));
        assertThrows(IllegalArgumentException.class, () -> TextureValue.parse(encode("not json")));
        assertThrows(IllegalArgumentException.class, () -> TextureValue.parse(encode("{\"textures\":{}}")));
        assertThrows(IllegalArgumentException.class, () -> TextureValue.parse(encode(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://evil.example.com/texture/" + TEXTURE + "\"}}}")));
        assertThrows(IllegalArgumentException.class, () -> TextureValue.parse(encode(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc\"}}}")));
        assertThrows(IllegalArgumentException.class, () -> TextureValue.parse(encode(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + TEXTURE + "zz\"}}}")));
    }

    @Test
    void aDefaultSkinIsToldApart() {
        assertTrue(TextureValue.withoutSkin(encode("{\"profileName\":\"Steve\",\"textures\":{}}")));
        assertFalse(TextureValue.withoutSkin(TestSkins.value(TEXTURE, false)));
        assertFalse(TextureValue.withoutSkin("garbage"));
    }

    @Test
    void theSkinRecordAgrees() {
        Skin skin = new Skin("49c88354a0494b6b", TEXTURE, TestSkins.value(TEXTURE, false), "c2ln", false, "player:Rancher");
        assertEquals("https://textures.minecraft.net/texture/" + TEXTURE, skin.textureUrl());
        assertThrows(IllegalArgumentException.class, () -> new Skin("49c88354a0494b6c", TEXTURE, "v", "s", false, ""));
    }
}
