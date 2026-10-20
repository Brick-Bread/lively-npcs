package dev.livelynpcs.skin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MineSkin API v2, from hand-written answers shaped like its OpenAPI spec (github.com/MineSkin/docs,
 * spec/openapi.json: QueueSuccessResponse, QueueProcessingResponse, the error responses) and the
 * error codes in its docs (wiki/common-errors.md).
 */
class MineSkinApiTest {

    private static final String TEXTURE = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String VALUE = TestSkins.value(TEXTURE, true);
    private static final String SIGNATURE = "U2lnbmVkIGJ5IE1vamFuZw==";
    private static final String RATE = "\"rateLimit\": {\"next\": {\"absolute\": 1760900001000, \"relative\": 0},"
            + " \"delay\": {\"millis\": 0, \"seconds\": 0}, \"limit\": {\"limit\": 60, \"remaining\": 59, \"minute\": {\"limit\": 60, \"remaining\": 59}}}";

    /** A clock that only moves when slept. */
    static final class FakeClock implements MineSkinApi.Clock {
        long now = 1_760_900_000_000L;
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }

    private static String job(String status, String extra) {
        return "{\"id\": \"6702d6f4e3a1c2b3d4e5f601\", \"status\": \"" + status + "\", \"timestamp\": 1760900000000" + extra + "}";
    }

    private static String skin() {
        return "{\"uuid\": \"c891dfac4cd247a2a55743e7e82ce76f\", \"name\": \"\", \"visibility\": \"public\", \"variant\": \"slim\","
                + " \"texture\": {\"data\": {\"value\": \"" + VALUE + "\", \"signature\": \"" + SIGNATURE + "\"},"
                + " \"hash\": {\"skin\": \"" + TEXTURE + "\"}, \"url\": {\"skin\": \"https://textures.minecraft.net/texture/" + TEXTURE + "\"}},"
                + " \"generator\": {\"version\": \"2.0.0\", \"timestamp\": 1760900002000, \"duration\": 1834, \"account\": \"acc\","
                + " \"server\": \"gen-1\"}, \"views\": 1, \"duplicate\": false}";
    }

    private static String processing() {
        return "{\"success\": true, \"job\": " + job("waiting", ", \"eta\": 1760900002500") + ", " + RATE
                + ", \"usage\": {\"credits\": {\"used\": 1, \"remaining\": 99}}, \"errors\": [], \"warnings\": [],"
                + " \"messages\": [{\"code\": \"ok\", \"message\": \"Job queued\"}],"
                + " \"links\": {\"self\": \"/v2/queue/6702d6f4e3a1c2b3d4e5f601\"}}";
    }

    private static String completed(boolean withSkin) {
        return "{\"success\": true, \"job\": " + job("completed", ", \"result\": \"c891dfac4cd247a2a55743e7e82ce76f\"")
                + (withSkin ? ", \"skin\": " + skin() : "") + ", " + RATE + ", \"errors\": [], \"messages\": []}";
    }

    private static String error(String code, String message) {
        return "{\"success\": false, \"errors\": [{\"code\": \"" + code + "\", \"message\": \"" + message + "\"}], " + RATE + "}";
    }

    @Test
    void aLinkIsQueuedThenCheckedUntilItsSkinIsReady() throws SkinException {
        FakeHttp http = new FakeHttp().answer(202, processing())
                .answer(200, "{\"success\": true, \"job\": " + job("active", "") + "}")
                .answer(200, completed(true));
        FakeClock clock = new FakeClock();
        MineSkinApi.Signed s = new MineSkinApi(http, "LivelyNPCs/test", clock).fromUrl("msk_key", "https://i.imgur.com/kQPrtYw.png", true);
        assertEquals(VALUE, s.value());
        assertEquals(SIGNATURE, s.signature());

        FakeHttp.Call queue = http.calls.get(0);
        assertEquals("POST", queue.method());
        assertEquals("https://api.mineskin.org/v2/queue", queue.url());
        assertEquals("Bearer msk_key", queue.headers().get("Authorization"));
        assertEquals("LivelyNPCs/test", queue.headers().get("User-Agent"));
        assertEquals("application/json", queue.headers().get("Content-Type"));
        Map<String, Object> body = Json.object(queue.text());
        assertEquals("https://i.imgur.com/kQPrtYw.png", body.get("url"));
        assertEquals("slim", body.get("variant"));
        assertEquals("https://api.mineskin.org/v2/queue/6702d6f4e3a1c2b3d4e5f601", http.calls.get(1).url());
        assertEquals("GET", http.calls.get(1).method());
        // The first check waits for the job's eta (2.5 s), then at most one check a second.
        assertEquals(List.of(2500L, 1000L), clock.sleeps);
    }

    @Test
    void aSkinMineSkinAlreadyHasIsThereAtOnce() throws SkinException {
        FakeHttp http = new FakeHttp().answer(200, completed(true));
        MineSkinApi.Signed s = new MineSkinApi(http, "t", new FakeClock()).fromUrl("k", "https://example.com/s.png", false);
        assertEquals(VALUE, s.value());
        assertEquals(1, http.calls.size());
        assertEquals("classic", Json.object(http.calls.getFirst().text()).get("variant"));
    }

    @Test
    void aCompletedJobWithoutItsSkinIsFetchedByTheResult() throws SkinException {
        FakeHttp http = new FakeHttp().answer(202, processing()).answer(200, completed(false))
                .answer(200, "{\"success\": true, \"skin\": " + skin() + "}");
        MineSkinApi.Signed s = new MineSkinApi(http, "t", new FakeClock()).fromUrl("k", "https://example.com/s.png", true);
        assertEquals(SIGNATURE, s.signature());
        assertEquals("https://api.mineskin.org/v2/skins/c891dfac4cd247a2a55743e7e82ce76f", http.calls.get(2).url());
        // The spec also allows the skin itself as the answer.
        FakeHttp bare = new FakeHttp().answer(202, processing()).answer(200, completed(false)).answer(200, skin());
        assertEquals(VALUE, new MineSkinApi(bare, "t", new FakeClock()).fromUrl("k", "https://example.com/s.png", true).value());
    }

    @Test
    void anUploadIsMultipartWithTheFileField() throws SkinException {
        byte[] png = SkinImage.png(TestSkins.unique(64, 64, 21));
        FakeHttp http = new FakeHttp().answer(200, completed(true));
        new MineSkinApi(http, "t", new FakeClock()).fromUpload("k", png, false);
        FakeHttp.Call call = http.calls.getFirst();
        String type = call.headers().get("Content-Type");
        assertTrue(type.startsWith("multipart/form-data; boundary="), type);
        String boundary = type.substring(type.indexOf('=') + 1);
        String text = new String(call.body(), StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--" + boundary + "\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"variant\"\r\n\r\nclassic\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n"));
        assertTrue(text.endsWith("\r\n--" + boundary + "--\r\n"));
        assertTrue(text.contains(new String(png, StandardCharsets.ISO_8859_1)), "the image bytes as they are");
    }

    @Test
    void aRateLimitIsWaitedOutWhileThereIsTime() throws SkinException {
        String limited = "{\"success\": false, \"errors\": [{\"code\": \"rate_limit\", \"message\": \"Too many requests\"}],"
                + " \"rateLimit\": {\"next\": {\"absolute\": 1760900004000, \"relative\": 4000}, \"delay\": {\"millis\": 1000},"
                + " \"limit\": {\"limit\": 20, \"remaining\": 0}}}";
        FakeHttp http = new FakeHttp().answer(429, limited).answer(200, completed(true));
        FakeClock clock = new FakeClock();
        new MineSkinApi(http, "t", clock).fromUrl("k", "https://example.com/s.png", false);
        assertEquals(List.of(4000L), clock.sleeps);

        // Retry-After when the body says nothing
        FakeHttp header = new FakeHttp().answer(429, "Too Many Requests", Map.of("Retry-After", "3")).answer(200, completed(true));
        FakeClock clock2 = new FakeClock();
        new MineSkinApi(header, "t", clock2).fromUrl("k", "https://example.com/s.png", false);
        assertEquals(List.of(3000L), clock2.sleeps);
    }

    @Test
    void aRateLimitLongerThanTheWaitIsReported() {
        String limited = "{\"success\": false, \"errors\": [{\"code\": \"rate_limit\", \"message\": \"Too many requests\"}],"
                + " \"rateLimit\": {\"next\": {\"absolute\": 0, \"relative\": 90000}, \"delay\": {\"millis\": 1000}, \"limit\": {\"limit\": 20, \"remaining\": 0}}}";
        SkinException e = assertThrows(SkinException.class,
                () -> new MineSkinApi(new FakeHttp().answer(429, limited), "t", new FakeClock()).fromUrl("k", "https://e.com/s.png", false));
        assertTrue(e.getMessage().contains("try again in 90 seconds"), e.getMessage());
    }

    @Test
    void theNextJobWaitsForTheRateLimitsNextRequest() throws SkinException {
        String queuedWithDelay = processing().replace("\"relative\": 0", "\"relative\": 2000");
        FakeHttp http = new FakeHttp().answer(202, queuedWithDelay).answer(200, completed(true)).answer(200, completed(true));
        FakeClock clock = new FakeClock();
        MineSkinApi api = new MineSkinApi(http, "t", clock);
        api.fromUrl("k", "https://example.com/a.png", false);
        long before = clock.sleeps.stream().mapToLong(Long::longValue).sum();
        assertEquals(2500L, before);
        // 2 s had to pass after the first job was queued; 2.5 s already did.
        api.fromUrl("k", "https://example.com/b.png", false);
        assertEquals(1, clock.sleeps.size());
    }

    @Test
    void errorsAreToldForPeople() {
        assertMessage(400, error("invalid_image", "Invalid image dimensions: 32x32"), "does not take that image as a skin (Invalid image dimensions: 32x32)");
        assertMessage(400, error("invalid_image_url", "Failed to find image from url"), "could not get a skin image from that link");
        assertMessage(401, error("invalid_api_key", "Invalid API key"), "refused the key in skins.mineskin-key (Invalid API key)");
        assertMessage(403, "{\"success\": false}", "refused the key");
        assertMessage(500, error("skin_change_failed", "Failed to change skin"), "Mojang would not take that skin");
        assertMessage(500, error("no_account_available", "No account available"), "could not make the skin (No account available); try again later");
        assertMessage(502, "<html>Bad Gateway</html>", "answered HTTP 502 without details");
        assertMessage(400, error("validation_error", "Invalid variant"), "did not accept the request (Invalid variant)");
    }

    private static void assertMessage(int status, String body, String expected) {
        SkinException e = assertThrows(SkinException.class,
                () -> new MineSkinApi(new FakeHttp().answer(status, body), "t", new FakeClock()).fromUrl("k", "https://e.com/s.png", false));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    void aFailedJobSaysWhy() {
        FakeHttp http = new FakeHttp().answer(202, processing()).answer(200, "{\"success\": false, \"job\": " + job("failed", "")
                + ", \"errors\": [{\"code\": \"skin_change_failed\", \"message\": \"Mojang rejected the skin\"}]}");
        SkinException e = assertThrows(SkinException.class,
                () -> new MineSkinApi(http, "t", new FakeClock()).fromUrl("k", "https://e.com/s.png", false));
        assertTrue(e.getMessage().contains("Mojang rejected the skin"), e.getMessage());
    }

    @Test
    void aJobThatNeverFinishesGivesUp() {
        FakeHttp http = new FakeHttp().answer(202, processing());
        for (int i = 0; i < 100; i++) http.answer(200, "{\"success\": true, \"job\": " + job("waiting", "") + "}");
        FakeClock clock = new FakeClock();
        SkinException e = assertThrows(SkinException.class,
                () -> new MineSkinApi(http, "t", clock).fromUrl("k", "https://e.com/s.png", false));
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertTrue(clock.now - 1_760_900_000_000L <= MineSkinApi.DEADLINE_MILLIS);
    }

    @Test
    void noKeyNoRequest() {
        FakeHttp http = new FakeHttp();
        SkinException e = assertThrows(SkinException.class, () -> new MineSkinApi(http, "t", new FakeClock()).fromUrl(" ", "https://e.com/s.png", false));
        assertTrue(e.getMessage().contains("skins.mineskin-key"), e.getMessage());
        assertTrue(http.calls.isEmpty());
    }
}
