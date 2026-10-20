package dev.livelynpcs.skin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mojang's answers, as documented on the Minecraft wiki (Mojang API), parsed without a network. */
class MojangApiTest {

    private static final String TEXTURE = "292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680";
    private static final String PROFILE = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";

    private static String session(String value, String signature) {
        return "{\n  \"id\" : \"069a79f444e94726a5befca90e38aaf5\",\n  \"name\" : \"Notch\",\n  \"properties\" : [ {\n"
                + "    \"name\" : \"textures\",\n    \"value\" : \"" + value + "\""
                + (signature == null ? "" : ",\n    \"signature\" : \"" + signature + "\"") + "\n  } ],\n  \"profileActions\" : [ ]\n}";
    }

    @Test
    void aPlayersSkinIsLookedUpInTwoSteps() throws SkinException {
        String value = TestSkins.value(TEXTURE, false);
        FakeHttp http = new FakeHttp().answer(200, PROFILE).answer(200, session(value, "c2lnbmF0dXJl"));
        MojangApi.Signed signed = new MojangApi(http, "LivelyNPCs/test").lookup("notch");
        assertEquals(value, signed.value());
        assertEquals("c2lnbmF0dXJl", signed.signature());
        assertEquals("Notch", signed.name(), "the name as Mojang spells it");
        assertEquals("https://api.mojang.com/users/profiles/minecraft/notch", http.calls.get(0).url());
        assertEquals("https://sessionserver.mojang.com/session/minecraft/profile/069a79f444e94726a5befca90e38aaf5?unsigned=false",
                http.calls.get(1).url());
        assertEquals("LivelyNPCs/test", http.calls.get(0).headers().get("User-Agent"));
    }

    @Test
    void noSuchPlayer() {
        for (int status : new int[]{204, 404}) {
            String body = status == 404 ? "{\"path\":\"/users/profiles/minecraft/nobodyxyz\",\"errorMessage\":\"Couldn't find any profile with name nobodyxyz\"}" : "";
            SkinException e = assertThrows(SkinException.class,
                    () -> new MojangApi(new FakeHttp().answer(status, body), "t").lookup("nobodyxyz"));
            assertEquals("No Minecraft player is called nobodyxyz.", e.getMessage());
        }
    }

    @Test
    void namesThatCannotBeArePlayersAreRefusedWithoutAsking() {
        FakeHttp http = new FakeHttp();
        assertThrows(SkinException.class, () -> new MojangApi(http, "t").lookup("no spaces"));
        assertThrows(SkinException.class, () -> new MojangApi(http, "t").lookup("../../x"));
        assertThrows(SkinException.class, () -> new MojangApi(http, "t").lookup("seventeen_letters"));
        assertTrue(http.calls.isEmpty());
    }

    @Test
    void busyOrBrokenAnswersSaySo() {
        SkinException limited = assertThrows(SkinException.class,
                () -> new MojangApi(new FakeHttp().answer(429, "{\"error\":\"TooManyRequestsException\"}"), "t").lookup("Notch"));
        assertTrue(limited.getMessage().contains("try again in a minute"), limited.getMessage());
        SkinException broken = assertThrows(SkinException.class,
                () -> new MojangApi(new FakeHttp().answer(200, "<html>"), "t").lookup("Notch"));
        assertTrue(broken.getMessage().contains("unexpected"), broken.getMessage());
        SkinException down = assertThrows(SkinException.class,
                () -> new MojangApi(new FakeHttp().answer(200, PROFILE).answer(503, ""), "t").lookup("Notch"));
        assertTrue(down.getMessage().contains("503"), down.getMessage());
    }

    @Test
    void aDefaultSkinOrAnUnsignedOneIsExplained() {
        String steve = Base64.getEncoder().encodeToString(
                "{\"profileName\":\"Notch\",\"textures\":{}}".getBytes(StandardCharsets.UTF_8));
        SkinException none = assertThrows(SkinException.class,
                () -> new MojangApi(new FakeHttp().answer(200, PROFILE).answer(200, session(steve, "c2ln")), "t").lookup("Notch"));
        assertTrue(none.getMessage().contains("default skins"), none.getMessage());
        SkinException unsigned = assertThrows(SkinException.class, () -> new MojangApi(new FakeHttp().answer(200, PROFILE)
                .answer(200, session(TestSkins.value(TEXTURE, false), null)), "t").lookup("Notch"));
        assertTrue(unsigned.getMessage().contains("unsigned"), unsigned.getMessage());
    }

    @Test
    void parsesTheDocumentedShapes() throws SkinException {
        assertArrayEquals(new String[]{"069a79f444e94726a5befca90e38aaf5", "Notch"}, MojangApi.parseProfile(PROFILE));
        assertThrows(SkinException.class, () -> MojangApi.parseProfile("{\"name\":\"Notch\"}"));
        assertArrayEquals(new String[]{"dmFsdWU=", "c2ln"}, MojangApi.parseTextures(session("dmFsdWU=", "c2ln"), "Notch"));
        assertThrows(SkinException.class, () -> MojangApi.parseTextures("{\"properties\":[]}", "Notch"));
    }
}
