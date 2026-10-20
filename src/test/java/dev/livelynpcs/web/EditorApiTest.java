package dev.livelynpcs.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.livelynpcs.anim.Animation;
import dev.livelynpcs.anim.Channel;
import dev.livelynpcs.anim.Easing;
import dev.livelynpcs.npc.NpcDef;
import dev.livelynpcs.web.Fakes.FakeAnimations;
import dev.livelynpcs.web.Fakes.FakeNpcs;
import dev.livelynpcs.web.Fakes.FakeSkins;
import dev.livelynpcs.web.Fakes.TestHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorApiTest {

    private TestHost host;
    private FakeNpcs npcs;
    private FakeAnimations animations;
    private FakeSkins skins;
    private EditorApi api;
    private String shopSkin;

    @BeforeEach
    void setUp() {
        host = new TestHost();
        npcs = new FakeNpcs();
        animations = new FakeAnimations();
        skins = new FakeSkins();
        api = new EditorApi(host, null, npcs, animations, skins, 300);

        shopSkin = skins.store(Fakes.skin("shop", false, "player:MobShopKeeper")).key();
        npcs.map.put("mobshop", Fakes.person("mobshop", shopSkin));
        npcs.map.put("farmer", Fakes.villager("farmer"));
        for (String id : List.of("greet", "talk", "beckon")) {
            animations.presets.put(id, Fakes.anim(id, 20, true, Map.of("at", 0), Map.of("at", 10, "right-arm-forward", 90), Map.of("at", 20)));
        }
        animations.presets.put("nod", Fakes.anim("nod", 20, true, Map.of("at", 0), Map.of("at", 4, "head-pitch", 12), Map.of("at", 20)));
        animations.presets.put("wave", Fakes.anim("wave", 44, true, Map.of("at", 0), Map.of("at", 8, "right-arm-forward", 160), Map.of("at", 44)));
        // The server's own: wave edited, nod saved unchanged, and one of its own.
        animations.files.put("wave", Fakes.anim("wave", 44, false, Map.of("at", 0), Map.of("at", 8, "right-arm-forward", 170), Map.of("at", 44)));
        animations.files.put("nod", animations.presets.get("nod").withId("nod", "Nod"));
        animations.files.put("dance", Fakes.anim("dance", 32, false, Map.of("at", 0), Map.of("at", 16, "root-y", 2, "ease", "ease-in-out")));
        online();
    }

    private void online() {
        host.online = Fakes.player(host.editor, "Brick_Bread", Fakes.world("world"), 10.123456, 64, -3.98765, 271.3f);
    }

    private FakeExchange call(String method, String path, String body) throws IOException {
        FakeExchange ex = new FakeExchange(method, path, TestHost.TOKEN, body);
        api.handle(ex);
        return ex;
    }

    private FakeExchange call(String method, String path) throws IOException {
        return call(method, path, null);
    }

    private static void assertError(FakeExchange ex, int status, String containing) {
        assertEquals(status, ex.status(), () -> "status of " + ex.text());
        String error = ex.error();
        assertTrue(error.toLowerCase().contains(containing.toLowerCase()), () -> "'" + error + "' should mention '" + containing + "'");
    }

    private static List<String> keys(JsonObject o) {
        return new ArrayList<>(o.keySet());
    }

    private static final List<String> NPC_FIELDS = Arrays.stream(NpcDef.class.getRecordComponents()).map(RecordComponent::getName).toList();

    // ------------------------------------------------------------------ logins

    @Nested
    class Login {

        @Test
        void noTokenIs401() throws IOException {
            FakeExchange ex = new FakeExchange("GET", "/api/state", null, null);
            api.handle(ex);
            assertError(ex, 401, "/npc editor");
            assertEquals(0, host.syncCalls);
        }

        @Test
        void wrongTokenIs401() throws IOException {
            FakeExchange ex = new FakeExchange("GET", "/api/state", "someone-elses-token-000000", null);
            api.handle(ex);
            assertEquals(401, ex.status());
        }

        @Test
        void expiredSessionIs401() throws IOException {
            host.expiresAt = System.currentTimeMillis() - 1;
            assertError(call("GET", "/api/state"), 401, "expired");
        }

        @ParameterizedTest
        @CsvSource({"GET,/api/state", "PUT,/api/npcs/mobshop", "POST,/api/npcs", "DELETE,/api/npcs/mobshop", "POST,/api/npcs/mobshop/move-to-me",
                "PUT,/api/animations/wave", "DELETE,/api/animations/dance", "POST,/api/preview", "POST,/api/preview/stop", "POST,/api/play",
                "POST,/api/skins", "GET,/api/skins/0123456789abcdef.png", "GET,/api/nothing"})
        void everyCallNeedsALogin(String method, String path) throws IOException {
            FakeExchange ex = new FakeExchange(method, path, null, "{\"id\":\"x\"}");
            api.handle(ex);
            assertEquals(401, ex.status());
            assertEquals(0, host.syncCalls, "nothing may run for a stranger");
            assertTrue(npcs.log.isEmpty());
            assertEquals(2, npcs.map.size());
        }
    }

    // ------------------------------------------------------------------ state

    @Nested
    class State {

        @Test
        void hasEverythingThePageNeeds() throws IOException {
            FakeExchange ex = call("GET", "/api/state");
            assertEquals(200, ex.status());
            assertTrue(ex.getResponseHeaders().getFirst("Content-Type").startsWith("application/json"));
            assertEquals(List.of("player", "npcs", "animations", "skins", "items", "channels", "easings", "settings", "session"), keys(ex.object()));
        }

        @Test
        void playerOnline() throws IOException {
            JsonObject p = call("GET", "/api/state").object().getAsJsonObject("player");
            assertEquals(List.of("name", "uuid", "online", "world"), keys(p));
            assertEquals("Brick_Bread", p.get("name").getAsString());
            assertEquals(host.editor.toString(), p.get("uuid").getAsString());
            assertTrue(p.get("online").getAsBoolean());
            assertEquals("world", p.get("world").getAsString());
        }

        @Test
        void playerOffline() throws IOException {
            host.online = null;
            host.knownName = "Brick_Offline";
            JsonObject p = call("GET", "/api/state").object().getAsJsonObject("player");
            assertEquals("Brick_Offline", p.get("name").getAsString());
            assertFalse(p.get("online").getAsBoolean());
            assertEquals("", p.get("world").getAsString());
        }

        @Test
        void npcsHaveExactlyNpcDefsFields() throws IOException {
            JsonArray list = call("GET", "/api/state").object().getAsJsonArray("npcs");
            assertEquals(2, list.size());
            for (JsonElement e : list) assertEquals(NPC_FIELDS, keys(e.getAsJsonObject()));
            JsonObject shop = list.get(1).getAsJsonObject();
            assertEquals("mobshop", shop.get("id").getAsString());
            assertEquals(shopSkin, shop.get("skin").getAsString());
            assertEquals(6.5, shop.get("x").getAsDouble());
            assertEquals(180, shop.get("yaw").getAsFloat());
            assertEquals("<gold><bold>Mob Shop", shop.getAsJsonArray("plate").get(0).getAsString());
            assertEquals("player:shop mobs", shop.getAsJsonArray("click").get(0).getAsString());
            assertTrue(shop.get("look").getAsBoolean());
        }

        @Test
        void animationsSayWhatIsAPresetAndWhatWasEdited() throws IOException {
            JsonArray list = call("GET", "/api/state").object().getAsJsonArray("animations");
            Map<String, JsonObject> byId = new java.util.TreeMap<>();
            for (JsonElement e : list) byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            assertEquals(List.of("beckon", "dance", "greet", "nod", "talk", "wave"), new ArrayList<>(byId.keySet()));
            JsonObject wave = byId.get("wave");
            assertEquals(List.of("id", "builtin", "edited", "name", "length", "loop", "glance", "keyframes"), keys(wave));
            assertTrue(wave.get("builtin").getAsBoolean());
            assertTrue(wave.get("edited").getAsBoolean(), "a saved file that differs from the preset is edited");
            assertEquals(170, wave.getAsJsonArray("keyframes").get(1).getAsJsonObject().get("right-arm-forward").getAsDouble());
            assertTrue(byId.get("nod").get("builtin").getAsBoolean());
            assertFalse(byId.get("nod").get("edited").getAsBoolean(), "a saved file the same as the preset is not edited");
            assertFalse(byId.get("greet").get("edited").getAsBoolean());
            assertFalse(byId.get("dance").get("builtin").getAsBoolean());
            assertFalse(byId.get("dance").get("edited").getAsBoolean());
            JsonObject key = byId.get("dance").getAsJsonArray("keyframes").get(1).getAsJsonObject();
            assertEquals("ease-in-out", key.get("ease").getAsString());
            assertEquals(2, key.get("root-y").getAsDouble());
        }

        @Test
        void skinsItemsChannelsEasingsSettings() throws IOException {
            JsonObject o = call("GET", "/api/state").object();
            JsonObject skin = o.getAsJsonArray("skins").get(0).getAsJsonObject();
            assertEquals(List.of("key", "slim", "source"), keys(skin));
            assertEquals(shopSkin, skin.get("key").getAsString());
            assertEquals("player:MobShopKeeper", skin.get("source").getAsString());

            assertEquals(host.items.size(), o.getAsJsonArray("items").size());
            assertEquals("wheat", o.getAsJsonArray("items").get(3).getAsString());

            JsonArray channels = o.getAsJsonArray("channels");
            assertEquals(Channel.COUNT, channels.size());
            for (int i = 0; i < Channel.COUNT; i++) {
                JsonObject c = channels.get(i).getAsJsonObject();
                Channel ch = Channel.ALL[i];
                assertEquals(List.of("key", "pixels", "min", "max"), keys(c));
                assertEquals(ch.key, c.get("key").getAsString());
                assertEquals(ch.pixels, c.get("pixels").getAsBoolean());
                assertEquals(ch.min, c.get("min").getAsFloat());
                assertEquals(ch.max, c.get("max").getAsFloat());
            }
            JsonArray easings = o.getAsJsonArray("easings");
            assertEquals(Easing.values().length, easings.size());
            assertEquals("ease-in-out", easings.get(3).getAsString());
            JsonObject settings = o.getAsJsonObject("settings");
            assertEquals(0.9375, settings.get("scale").getAsDouble(), 1e-9);
            assertEquals(2, settings.get("keyTicks").getAsInt());
            long left = o.getAsJsonObject("session").get("expiresIn").getAsLong();
            assertTrue(left > 3_500_000 && left <= 3_600_000, "time left " + left);
        }

        @Test
        void busyMainThreadIs503() throws IOException {
            host.busy = true;
            assertError(call("GET", "/api/state"), 503, "busy");
        }

        @Test
        void onlyGet() throws IOException {
            assertEquals(405, call("POST", "/api/state", "{}").status());
        }
    }

    // ------------------------------------------------------------------ NPCs

    @Nested
    class SaveNpc {

        private JsonObject shop() throws IOException {
            JsonObject o = call("GET", "/api/state").object().getAsJsonArray("npcs").get(1).getAsJsonObject();
            assertEquals("mobshop", o.get("id").getAsString());
            return o;
        }

        @Test
        void savesAndAnswersTheStoredNpc() throws IOException {
            JsonObject o = shop();
            JsonArray plate = new JsonArray();
            plate.add("<red>Closed");
            o.add("plate", plate);
            o.addProperty("held", "minecraft:Diamond");
            o.addProperty("yaw", 270);
            FakeExchange ex = call("PUT", "/api/npcs/mobshop", o.toString());
            assertEquals(200, ex.status(), ex::text);
            JsonObject back = ex.object();
            assertEquals(NPC_FIELDS, keys(back));
            assertEquals("<red>Closed", back.getAsJsonArray("plate").get(0).getAsString());
            assertEquals("diamond", back.get("held").getAsString(), "item ids are kept short and lower case");
            assertEquals(-90, back.get("yaw").getAsFloat(), "yaw is kept in -180..180");
            NpcDef stored = npcs.map.get("mobshop");
            assertEquals(List.of("<red>Closed"), stored.plate());
            assertEquals("diamond", stored.held());
        }

        @Test
        void fieldsLeftOutKeepTheirValues() throws IOException {
            FakeExchange ex = call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"look\":false}");
            assertEquals(200, ex.status(), ex::text);
            NpcDef n = npcs.map.get("mobshop");
            assertFalse(n.look());
            assertEquals(shopSkin, n.skin());
            assertEquals("wheat", n.held());
            assertEquals(6.5, n.x());
        }

        @Test
        void aNewIdNeedsAPlace() throws IOException {
            assertError(call("PUT", "/api/npcs/newbie", "{\"id\":\"newbie\"}"), 400, "world");
            assertError(call("PUT", "/api/npcs/newbie", "{\"id\":\"newbie\",\"world\":\"world\",\"x\":1,\"y\":2}"), 400, "(z) is missing");
            FakeExchange ok = call("PUT", "/api/npcs/newbie", "{\"id\":\"newbie\",\"world\":\"world\",\"x\":1,\"y\":64,\"z\":2}");
            assertEquals(200, ok.status(), ok::text);
            NpcDef n = npcs.map.get("newbie");
            assertEquals(List.of("<bold>newbie"), n.plate());
            assertEquals("greet", n.greet());
            assertEquals("player", n.entity());
        }

        @Test
        void clickActionsAreChecked() throws IOException {
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"click\":[\"shop mobs\"]}"), 400, "player:, console: or message:");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"click\":[\"console:  \"]}"), 400, "does not say");
            FakeExchange ok = call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"click\":[\"Player:/shop mobs\",\"message:<green>Hi {player}\"]}");
            assertEquals(200, ok.status(), ok::text);
            assertEquals(List.of("player:shop mobs", "message:<green>Hi {player}"), npcs.map.get("mobshop").click());
        }

        @Test
        void animationsMustExist() throws IOException {
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"greet\":\"moonwalk\"}"), 400, "no animation called 'moonwalk'");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"idle\":[\"nod\",\"moonwalk\"]}"), 400, "idle");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"idle\":[\"none\",\"nod\"]}"), 400, "'none'");
            FakeExchange ok = call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"idle\":[\"none\"],\"greet\":\"\",\"beckon\":\"dance\"}");
            assertEquals(200, ok.status(), ok::text);
            NpcDef n = npcs.map.get("mobshop");
            assertEquals(List.of("none"), n.idle());
            assertEquals("", n.greet());
            assertEquals("dance", n.beckon());
        }

        @Test
        void worldsSkinsItemsAndMobsMustExist() throws IOException {
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"world\":\"moon\"}"), 400, "no world called 'moon'");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"skin\":\"0123456789abcdef\"}"), 400, "no stored skin");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"icon\":\"banana\"}"), 400, "'banana' is not an item");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"entity\":\"dragon_of_doom\"}"), 400, "not a mob");
            FakeExchange ok = call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"entity\":\"minecraft:villager\",\"skin\":\"\",\"held\":\"\"}");
            assertEquals(200, ok.status(), ok::text);
            assertEquals("villager", npcs.map.get("mobshop").entity());
        }

        @Test
        void typesAreChecked() throws IOException {
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"plate\":\"Hello\"}"), 400, "list");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"plate\":[1,2]}"), 400, "text");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"x\":\"far\"}"), 400, "number");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"x\":1e99}"), 400, "inside the world");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"look\":\"yes\"}"), 400, "true or false");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"skin\":7}"), 400, "text");
        }

        @Test
        void idMustMatchAndBeAnId() throws IOException {
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"other\"}"), 400, "does not match");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"plate\":[]}"), 400, "id is missing");
            assertError(call("PUT", "/api/npcs/Mob%20Shop", "{\"id\":\"Mob Shop\"}"), 400, "not an NPC id");
        }

        @Test
        void badBodies() throws IOException {
            assertError(call("PUT", "/api/npcs/mobshop", "{not json"), 400, "not JSON");
            assertError(call("PUT", "/api/npcs/mobshop", "[1,2]"), 400, "JSON object");
            assertError(call("PUT", "/api/npcs/mobshop", ""), 400, "empty");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\",\"plate\":[\"" + "x".repeat(WebServer.MAX_BODY) + "\"]}"), 413, "too big");
        }

        @Test
        void serviceRefusalsReachThePerson() throws IOException {
            npcs.refusePut = new IllegalArgumentException("mobshop: the world 'world' is not loaded");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\"}"), 400, "not loaded");
            npcs.refusePut = null;
            npcs.failPut = new IOException("disk full");
            assertError(call("PUT", "/api/npcs/mobshop", "{\"id\":\"mobshop\"}"), 500, "disk full");
            assertFalse(host.warnings.isEmpty(), "a failed save is logged");
        }

        @Test
        void otherMethodsAreRefused() throws IOException {
            assertEquals(405, call("POST", "/api/npcs/mobshop", "{}").status());
            assertEquals(405, call("GET", "/api/npcs").status());
        }
    }

    @Nested
    class CreateNpc {

        @Test
        void standsWhereTheEditorStands() throws IOException {
            FakeExchange ex = call("POST", "/api/npcs", "{\"id\":\"newbie\"}");
            assertEquals(201, ex.status(), ex::text);
            JsonObject o = ex.object();
            assertEquals(NPC_FIELDS, keys(o));
            assertEquals("world", o.get("world").getAsString());
            assertEquals(10.123, o.get("x").getAsDouble(), 1e-9);
            assertEquals(64, o.get("y").getAsDouble(), 1e-9);
            assertEquals(-3.988, o.get("z").getAsDouble(), 1e-9);
            assertEquals(-88.7, o.get("yaw").getAsFloat(), 1e-4);
            assertEquals("player", o.get("entity").getAsString());
            assertEquals("<bold>newbie", o.getAsJsonArray("plate").get(0).getAsString());
            assertNotNull(npcs.map.get("newbie"));
        }

        @Test
        void needsTheEditorInGame() throws IOException {
            host.online = null;
            assertError(call("POST", "/api/npcs", "{\"id\":\"newbie\"}"), 409, "not in game");
            assertNull(npcs.map.get("newbie"));
        }

        @Test
        void copiesAnotherOneEvenWhenOffline() throws IOException {
            host.online = null;
            FakeExchange ex = call("POST", "/api/npcs", "{\"id\":\"shop-2\",\"copyFrom\":\"mobshop\"}");
            assertEquals(201, ex.status(), ex::text);
            NpcDef copy = npcs.map.get("shop-2");
            NpcDef from = npcs.map.get("mobshop");
            assertEquals(from.plate(), copy.plate());
            assertEquals(from.x(), copy.x());
            assertEquals(from.skin(), copy.skin());
            assertEquals(from.click(), copy.click());
        }

        @Test
        void refusals() throws IOException {
            assertError(call("POST", "/api/npcs", "{\"id\":\"mobshop\"}"), 409, "already");
            assertError(call("POST", "/api/npcs", "{\"id\":\"Bad Id\"}"), 400, "not an NPC id");
            assertError(call("POST", "/api/npcs", "{}"), 400, "'id' is missing");
            assertError(call("POST", "/api/npcs", "{\"id\":\"x\",\"copyFrom\":\"ghost\"}"), 404, "ghost");
        }
    }

    @Nested
    class DeleteAndMoveNpc {

        @Test
        void deletes() throws IOException {
            FakeExchange ex = call("DELETE", "/api/npcs/farmer");
            assertEquals(200, ex.status());
            assertEquals("farmer", ex.object().get("deleted").getAsString());
            assertNull(npcs.map.get("farmer"));
            assertError(call("DELETE", "/api/npcs/farmer"), 404, "no NPC called farmer");
        }

        @Test
        void movesToTheEditor() throws IOException {
            FakeExchange ex = call("POST", "/api/npcs/mobshop/move-to-me");
            assertEquals(200, ex.status(), ex::text);
            NpcDef n = npcs.map.get("mobshop");
            assertEquals(10.123, n.x(), 1e-9);
            assertEquals(-3.988, n.z(), 1e-9);
            assertEquals(-88.7f, n.yaw(), 1e-4);
            assertEquals(List.of("<gold><bold>Mob Shop", "<gray>Buy mobs"), n.plate(), "only the spot changes");
            assertEquals(10.123, ex.object().get("x").getAsDouble(), 1e-9);
        }

        @Test
        void moveRefusals() throws IOException {
            assertError(call("POST", "/api/npcs/ghost/move-to-me"), 404, "ghost");
            host.online = null;
            assertError(call("POST", "/api/npcs/mobshop/move-to-me"), 409, "not in game");
            assertEquals(6.5, npcs.map.get("mobshop").x());
            assertEquals(405, call("GET", "/api/npcs/mobshop/move-to-me").status());
        }
    }

    // ------------------------------------------------------------------ animations

    @Nested
    class SaveAnimation {

        private static final String HOP = "{\"name\":\"Big hop\",\"length\":12,\"loop\":false,\"glance\":true,"
                + "\"keyframes\":[{\"at\":0},{\"at\":4,\"ease\":\"ease-out\",\"root-y\":6.5},{\"at\":12,\"ease\":\"ease-in\"}]}";

        @Test
        void savesANewOne() throws IOException {
            FakeExchange ex = call("PUT", "/api/animations/big-hop", HOP);
            assertEquals(200, ex.status(), ex::text);
            JsonObject o = ex.object();
            assertEquals(List.of("id", "builtin", "edited", "name", "length", "loop", "glance", "keyframes"), keys(o));
            assertEquals("big-hop", o.get("id").getAsString());
            assertFalse(o.get("builtin").getAsBoolean());
            assertFalse(o.get("edited").getAsBoolean());
            assertTrue(o.get("glance").getAsBoolean());
            Animation a = animations.files.get("big-hop");
            assertEquals("Big hop", a.name());
            assertEquals(12, a.length());
            assertEquals(Easing.EASE_OUT, a.keyframes().get(1).ease());
            assertEquals(6.5f, a.keyframes().get(1).value(Channel.ROOT_Y));
        }

        @Test
        void savingAPresetMakesItEdited() throws IOException {
            String body = "{\"id\":\"talk\",\"builtin\":true,\"edited\":false,\"name\":\"Talk\",\"length\":20,\"keyframes\":[{\"at\":0},{\"at\":10,\"right-arm-forward\":45},{\"at\":20}]}";
            FakeExchange ex = call("PUT", "/api/animations/talk", body);
            assertEquals(200, ex.status(), ex::text);
            assertTrue(ex.object().get("builtin").getAsBoolean());
            assertTrue(ex.object().get("edited").getAsBoolean());
            assertFalse(animations.files.get("talk").builtin(), "a saved file is not the built-in one");
        }

        @Test
        void addsTheRestKeyframeAndStretchesTheLengthLikeTheFormat() throws IOException {
            FakeExchange ex = call("PUT", "/api/animations/late", "{\"length\":5,\"keyframes\":[{\"at\":10,\"head-yaw\":20}]}");
            assertEquals(200, ex.status(), ex::text);
            Animation a = animations.files.get("late");
            assertEquals(2, a.keyframes().size());
            assertEquals(0, a.keyframes().getFirst().at());
            assertEquals(10, a.length());
        }

        @Test
        void badShapes() throws IOException {
            assertError(call("PUT", "/api/animations/x", "{\"length\":10}"), 400, "no keyframes");
            assertError(call("PUT", "/api/animations/x", "{\"keyframes\":[{\"head-yaw\":3}]}"), 400, "no tick");
            assertError(call("PUT", "/api/animations/x", "{\"keyframes\":[{\"at\":2.5}]}"), 400, "whole number");
            assertError(call("PUT", "/api/animations/x", "{\"keyframes\":[{\"at\":-4}]}"), 400, "whole number");
            assertError(call("PUT", "/api/animations/x", "{\"keyframes\":[{\"at\":0},{\"at\":4},{\"at\":4}]}"), 400, "two keyframes at tick 4");
            assertError(call("PUT", "/api/animations/x", "{\"keyframes\":[[0]]}"), 400, "not a keyframe");
            assertError(call("PUT", "/api/animations/x", "{\"length\":\"long\",\"keyframes\":[]}"), 400, "length");
            assertError(call("PUT", "/api/animations/x", "{\"length\":0,\"keyframes\":[]}"), 400, "length");
            assertError(call("PUT", "/api/animations/x", "{\"loop\":\"yes\",\"keyframes\":[]}"), 400, "true or false");
            assertError(call("PUT", "/api/animations/x", "{\"name\":5,\"keyframes\":[]}"), 400, "name");
            assertError(call("PUT", "/api/animations/Not%20An%20Id", "{\"keyframes\":[]}"), 400, "not an animation id");
        }

        @Test
        void unreadableValuesAreErrorsNotSilentlyDropped() throws IOException {
            assertError(call("PUT", "/api/animations/x", "{\"keyframes\":[{\"at\":0,\"tail-wag\":4}]}"), 400, "no channel called 'tail-wag'");
            assertError(call("PUT", "/api/animations/x", "{\"keyframes\":[{\"at\":0,\"head-yaw\":\"left\"}]}"), 400, "not a number");
            assertError(call("PUT", "/api/animations/x", "{\"keyframes\":[{\"at\":4,\"ease\":\"bouncy\"}]}"), 400, "no ease called 'bouncy'");
            assertTrue(animations.files.get("x") == null);
        }

        @Test
        void noneIsNotAName() throws IOException {
            assertError(call("PUT", "/api/animations/none", HOP), 400, "means no animation");
            assertNull(animations.files.get("none"));
        }

        @Test
        void aFailedSaveIs500() throws IOException {
            animations.failSave = new IOException("read-only folder");
            assertError(call("PUT", "/api/animations/big-hop", HOP), 500, "read-only folder");
        }
    }

    @Nested
    class DeleteAnimation {

        @Test
        void deletesTheServersOwn() throws IOException {
            FakeExchange ex = call("DELETE", "/api/animations/dance");
            assertEquals(200, ex.status(), ex::text);
            assertEquals("dance", ex.object().get("deleted").getAsString());
            assertFalse(ex.object().has("animation"));
            assertNull(animations.files.get("dance"));
        }

        @Test
        void restoresAnEditedPreset() throws IOException {
            FakeExchange ex = call("DELETE", "/api/animations/wave");
            assertEquals(200, ex.status(), ex::text);
            JsonObject back = ex.object().getAsJsonObject("animation");
            assertEquals("wave", back.get("id").getAsString());
            assertTrue(back.get("builtin").getAsBoolean());
            assertFalse(back.get("edited").getAsBoolean());
            assertEquals(160, back.getAsJsonArray("keyframes").get(1).getAsJsonObject().get("right-arm-forward").getAsDouble());
        }

        @Test
        void nothingToDelete() throws IOException {
            assertError(call("DELETE", "/api/animations/greet"), 404, "nothing to restore");
            assertError(call("DELETE", "/api/animations/ghost"), 404, "no animation called ghost");
            assertEquals(405, call("GET", "/api/animations/greet").status());
        }
    }

    // ------------------------------------------------------------------ playing

    @Nested
    class Preview {

        private static final String DRAFT = "{\"npc\":\"mobshop\",\"animation\":{\"id\":\"draft-wave\",\"name\":\"Wave\",\"length\":20,\"loop\":true,"
                + "\"keyframes\":[{\"at\":0},{\"at\":10,\"right-arm-forward\":150}]}}";

        @Test
        void playsTheDraftForTheEditor() throws IOException {
            FakeExchange ex = call("POST", "/api/preview", DRAFT);
            assertEquals(200, ex.status(), ex::text);
            assertEquals(List.of("playing", "animation", "npc", "loop"), keys(ex.object()));
            assertTrue(ex.object().get("playing").getAsBoolean());
            assertEquals("draft-wave", ex.object().get("animation").getAsString());
            assertTrue(ex.object().get("loop").getAsBoolean());
            assertNotNull(npcs.previewed);
            assertEquals("draft-wave", npcs.previewed.id());
            assertTrue(npcs.previewed.loop());
            assertEquals(150f, npcs.previewed.keyframes().get(1).value(Channel.RIGHT_ARM_FORWARD));
            assertSame(host.online, npcs.previewedFor);
            assertNull(animations.files.get("draft-wave"), "a preview saves nothing");
        }

        @Test
        void anUnsavedDraftWithoutAGoodIdStillPlays() throws IOException {
            FakeExchange ex = call("POST", "/api/preview", "{\"npc\":\"mobshop\",\"animation\":{\"id\":\"New One!\",\"keyframes\":[{\"at\":0},{\"at\":8,\"head-yaw\":30,\"what\":1}]}}");
            assertEquals(200, ex.status(), ex::text);
            assertEquals("preview", npcs.previewed.id());
        }

        @Test
        void notSeeingTheModelIsAStateNotAnError() throws IOException {
            npcs.previewResult = false;
            FakeExchange ex = call("POST", "/api/preview", DRAFT);
            assertEquals(200, ex.status(), ex::text);
            assertFalse(ex.object().get("playing").getAsBoolean());
            assertTrue(ex.object().get("message").getAsString().contains("cannot see mobshop moving"), ex::text);
            assertTrue(ex.object().get("message").getAsString().contains("world"), ex::text);
            host.online = null;
            FakeExchange offline = call("POST", "/api/preview", DRAFT);
            assertEquals(200, offline.status());
            assertFalse(offline.object().get("playing").getAsBoolean());
            assertTrue(offline.object().get("message").getAsString().contains("not in game"));
        }

        @Test
        void refusals() throws IOException {
            assertError(call("POST", "/api/preview", "{\"npc\":\"ghost\",\"animation\":{\"keyframes\":[]}}"), 404, "ghost");
            assertError(call("POST", "/api/preview", "{\"npc\":\"farmer\",\"animation\":{\"keyframes\":[]}}"), 409, "villager");
            assertError(call("POST", "/api/preview", "{\"npc\":\"mobshop\"}"), 400, "draft");
            assertError(call("POST", "/api/preview", "{\"animation\":{\"keyframes\":[]}}"), 400, "'npc' is missing");
            assertError(call("POST", "/api/preview", "{\"npc\":\"mobshop\",\"animation\":{\"keyframes\":[{\"at\":1.5}]}}"), 400, "whole number");
        }

        @Test
        void stops() throws IOException {
            FakeExchange ex = call("POST", "/api/preview/stop", "{}");
            assertEquals(200, ex.status());
            assertTrue(npcs.stopped);
            npcs.stopped = false;
            host.online = null;
            assertEquals(200, call("POST", "/api/preview/stop").status(), "nothing to stop is fine");
            assertFalse(npcs.stopped);
            assertEquals(405, call("GET", "/api/preview/stop").status());
        }
    }

    @Nested
    class Play {

        @Test
        void forEveryone() throws IOException {
            FakeExchange ex = call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"dance\",\"everyone\":true}");
            assertEquals(200, ex.status(), ex::text);
            assertEquals(List.of("play mobshop dance everyone"), npcs.log);
            assertTrue(ex.object().get("everyone").getAsBoolean());
        }

        @Test
        void forTheEditor() throws IOException {
            assertEquals(200, call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"wave\"}").status());
            assertSame(host.online, npcs.playedFor);
            host.online = null;
            assertError(call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"wave\",\"everyone\":false}"), 409, "not in game");
            assertEquals(200, call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"wave\",\"everyone\":true}").status());
        }

        @Test
        void noneStopsIt() throws IOException {
            FakeExchange ex = call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"none\",\"everyone\":true}");
            assertEquals(200, ex.status(), ex::text);
            assertTrue(ex.object().get("stopped").getAsBoolean());
            assertEquals(List.of("mobshop everyone"), host.stops);
            assertEquals(200, call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"none\"}").status());
            assertEquals(List.of("mobshop everyone", "mobshop Brick_Bread"), host.stops);
            assertTrue(npcs.log.isEmpty(), "stopping plays nothing");
            assertError(call("POST", "/api/play", "{\"npc\":\"ghost\",\"animation\":\"none\",\"everyone\":true}"), 404, "ghost");
            host.canStop = false;
            assertError(call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"none\",\"everyone\":true}"), 409, "could not stop");
            host.online = null;
            assertError(call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"none\"}"), 409, "not in game");
        }

        @Test
        void refusals() throws IOException {
            assertError(call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"moonwalk\",\"everyone\":true}"), 404, "save it first");
            assertError(call("POST", "/api/play", "{\"npc\":\"ghost\",\"animation\":\"wave\",\"everyone\":true}"), 404, "ghost");
            assertError(call("POST", "/api/play", "{\"npc\":\"farmer\",\"animation\":\"wave\",\"everyone\":true}"), 409, "only person NPCs");
            assertError(call("POST", "/api/play", "{\"npc\":\"mobshop\"}"), 400, "'animation' is missing");
            assertError(call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"wave\",\"everyone\":\"yes\"}"), 400, "true or false");
            npcs.playResult = false;
            assertError(call("POST", "/api/play", "{\"npc\":\"mobshop\",\"animation\":\"wave\",\"everyone\":true}"), 409, "could not play");
        }
    }

    // ------------------------------------------------------------------ skins

    @Nested
    class Skins {

        @Test
        void byPlayerName() throws IOException {
            FakeExchange ex = call("POST", "/api/skins", "{\"type\":\"player\",\"value\":\"Alex\"}");
            assertEquals(200, ex.status(), ex::text);
            JsonObject o = ex.object();
            assertEquals(List.of("key", "slim", "source"), keys(o));
            assertTrue(o.get("slim").getAsBoolean());
            assertEquals("player:Alex", o.get("source").getAsString());
            assertNotNull(skins.map.get(o.get("key").getAsString()));
        }

        @Test
        void byLink() throws IOException {
            FakeExchange ex = call("POST", "/api/skins", "{\"type\":\"url\",\"value\":\"https://example.com/skin.png\",\"slim\":true}");
            assertEquals(200, ex.status(), ex::text);
            assertTrue(ex.object().get("slim").getAsBoolean());
            assertError(call("POST", "/api/skins", "{\"type\":\"url\",\"value\":\"ftp://example.com/a.png\"}"), 400, "not a web link");
            assertError(call("POST", "/api/skins", "{\"type\":\"url\",\"value\":\"\"}"), 400, "paste a link");
            assertError(call("POST", "/api/skins", "{\"type\":\"url\",\"value\":\"https://example.com/broken.png\"}"), 400, "MineSkin could not read");
        }

        @Test
        void byUpload() throws IOException {
            byte[] png = Fakes.png(64, 64);
            FakeExchange ex = call("POST", "/api/skins", "{\"type\":\"upload\",\"value\":\"" + Base64.getEncoder().encodeToString(png) + "\",\"slim\":false}");
            assertEquals(200, ex.status(), ex::text);
            assertArrayEquals(png, skins.uploaded);
            assertEquals("upload", ex.object().get("source").getAsString());
            FakeExchange dataUrl = call("POST", "/api/skins", "{\"type\":\"upload\",\"value\":\"data:image/png;base64," + Base64.getEncoder().encodeToString(Fakes.png(64, 32)) + "\"}");
            assertEquals(200, dataUrl.status(), dataUrl::text);
        }

        @Test
        void uploadsAreChecked() throws IOException {
            assertError(call("POST", "/api/skins", "{\"type\":\"upload\",\"value\":\"\"}"), 400, "empty");
            assertError(call("POST", "/api/skins", "{\"type\":\"upload\",\"value\":\"%%%not base64%%%\"}"), 400, "not an image");
            assertError(call("POST", "/api/skins", "{\"type\":\"upload\",\"value\":\"" + Base64.getEncoder().encodeToString("GIF89a, honest".repeat(3).getBytes()) + "\"}"), 400, "not a PNG");
            assertError(call("POST", "/api/skins", "{\"type\":\"upload\",\"value\":\"" + Base64.getEncoder().encodeToString(Fakes.png(128, 128)) + "\"}"), 400, "128x128");
            byte[] huge = new byte[EditorJson.MAX_SKIN_BYTES + 10];
            assertError(call("POST", "/api/skins", "{\"type\":\"upload\",\"value\":\"" + Base64.getEncoder().encodeToString(huge) + "\"}"), 400, "bigger than 1 MB");
            assertNull(skins.uploaded);
        }

        @Test
        void playerNamesAreChecked() throws IOException {
            assertError(call("POST", "/api/skins", "{\"type\":\"player\",\"value\":\"not a name!\"}"), 400, "not a player name");
            assertError(call("POST", "/api/skins", "{\"type\":\"player\"}"), 400, "type a player name");
            assertError(call("POST", "/api/skins", "{\"type\":\"player\",\"value\":\"Nobody\"}"), 400, "no player called Nobody");
        }

        @Test
        void waitsOnlySoLong() throws IOException {
            long start = System.nanoTime();
            assertError(call("POST", "/api/skins", "{\"type\":\"player\",\"value\":\"Slowpoke\"}"), 504, "took longer");
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
        }

        @Test
        void unknownWay() throws IOException {
            assertError(call("POST", "/api/skins", "{\"type\":\"telepathy\",\"value\":\"x\"}"), 400, "player, url or upload");
            assertError(call("POST", "/api/skins", "{\"value\":\"x\"}"), 400, "'type' is missing");
        }

        @Test
        void image() throws IOException {
            FakeExchange ex = call("GET", "/api/skins/" + shopSkin + ".png");
            assertEquals(200, ex.status(), ex::text);
            assertEquals("image/png", ex.getResponseHeaders().getFirst("Content-Type"));
            assertTrue(ex.getResponseHeaders().getFirst("Cache-Control").contains("max-age"));
            assertArrayEquals(skins.image, ex.bytes());
        }

        @Test
        void imageRefusals() throws IOException {
            assertError(call("GET", "/api/skins/0123456789abcdef.png"), 404, "no stored skin");
            assertError(call("GET", "/api/skins/..%2F..%2Fconfig.png"), 404, "no skin called");
            assertEquals(404, call("GET", "/api/skins/" + shopSkin).status(), "needs .png");
            assertEquals(405, call("DELETE", "/api/skins/" + shopSkin + ".png").status());
            skins.failPng = true;
            assertError(call("GET", "/api/skins/" + shopSkin + ".png"), 502, "did not answer");
        }
    }

    // ------------------------------------------------------------------ the rest

    @Test
    void unknownCallsAre404() throws IOException {
        assertError(call("GET", "/api/nothing"), 404, "no editor API call");
        assertError(call("GET", "/api/"), 404, "no editor API call");
        assertError(call("PUT", "/api/npcs/mobshop/hat", "{}"), 404, "no editor API call");
    }

    @Test
    void answersAreJsonWithAnErrorField() throws IOException {
        FakeExchange ex = call("GET", "/api/nothing");
        assertEquals(List.of("error"), keys(ex.object()));
        assertTrue(ex.getResponseHeaders().getFirst("Content-Type").startsWith("application/json"));
    }

    @Test
    void npcDefJsonRoundTrips() throws Exception {
        NpcDef shop = npcs.map.get("mobshop");
        String json = WebServer.gson().toJson(EditorJson.npc(shop));
        NpcDef back = EditorJson.readNpc("mobshop", com.google.gson.JsonParser.parseString(json), null,
                new EditorJson.NpcChecks(w -> true, k -> true, java.util.Set.of(), a -> true, java.util.Set.of()));
        assertEquals(shop, back);
    }
}
