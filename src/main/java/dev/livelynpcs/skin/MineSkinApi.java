package dev.livelynpcs.skin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skins signed through MineSkin's API v2 (https://docs.mineskin.org, spec/openapi.json in
 * github.com/MineSkin/docs): a job is queued with POST /v2/queue (a link as JSON, an image as
 * multipart/form-data with the field "file"), answered 200 when the skin is ready at once (a skin
 * MineSkin already has) or 202 with a job id; the job is then checked with GET /v2/queue/{jobId},
 * at most once a second, until its status is "completed" (the answer's "skin" carries
 * texture.data.value and .signature; else GET /v2/skins/{job.result}) or "failed". Every request
 * carries "Authorization: Bearer &lt;skins.mineskin-key&gt;" and a User-Agent. A 429 answer is waited out
 * (rateLimit.next.relative, or Retry-After) while time allows, and the next job is not queued before
 * rateLimit.next says. Errors come back as {"success": false, "errors": [{"code", "message"}]} and are
 * turned into messages for people. Blocking (up to {@link #DEADLINE_MILLIS}): call it off the main
 * thread. One instance per plugin, so jobs from the same server queue up politely. JDK only.
 */
public final class MineSkinApi {

    /** Time, made replaceable so tests do not wait. */
    public interface Clock {
        long now();

        void sleep(long millis) throws InterruptedException;

        Clock SYSTEM = new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }

            @Override
            public void sleep(long millis) throws InterruptedException {
                Thread.sleep(millis);
            }
        };
    }

    /** A signed textures property. */
    public record Signed(String value, String signature) {
    }

    static final String BASE = "https://api.mineskin.org";
    /** How long one skin may take, all waiting included (the web editor gives up after 60 s). */
    public static final long DEADLINE_MILLIS = 50_000;
    static final String NO_KEY = "Skins from an image need a MineSkin key: get one free at https://account.mineskin.org/keys "
            + "and put it in config.yml as skins.mineskin-key, then /npc reload.";
    private static final int MAX_ANSWER = 256 * 1024;

    private final Http http;
    private final String userAgent;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** Not before this (epoch millis) may the next job be queued, as MineSkin's rateLimit.next says. */
    private long nextSubmit;

    public MineSkinApi(Http http, String userAgent, Clock clock) {
        this.http = http;
        this.userAgent = userAgent;
        this.clock = clock;
    }

    /** A skin image at a web link (MineSkin downloads it), as a slim or classic skin. */
    public Signed fromUrl(String key, String url, boolean slim) throws SkinException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("variant", slim ? "slim" : "classic");
        return generate(key, Json.write(body).getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** A skin image (PNG bytes), uploaded, as a slim or classic skin. */
    public Signed fromUpload(String key, byte[] png, boolean slim) throws SkinException {
        byte[] noise = new byte[8];
        random.nextBytes(noise);
        String boundary = "LivelyNPCs-" + HexFormat.of().formatHex(noise);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
                + (slim ? "slim" : "classic") + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        body.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(png);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return generate(key, body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private Signed generate(String key, byte[] body, String contentType) throws SkinException {
        if (key == null || key.isBlank()) throw new SkinException(NO_KEY);
        long deadline = clock.now() + DEADLINE_MILLIS;
        Answer queued = submit(key.trim(), body, contentType, deadline);
        Signed ready = skinOf(queued.json());
        if (ready != null) return ready;
        String job = Json.string(queued.json(), "job", "id");
        if (job == null || job.isBlank() || job.length() > 200) {
            throw new SkinException("MineSkin queued the skin but sent no job to follow; try again.");
        }
        if ("failed".equals(Json.string(queued.json(), "job", "status"))) throw failure(queued);
        long wait = 1500;
        long eta = (long) Json.number(queued.json(), 0, "job", "eta");
        if (eta > clock.now()) wait = Math.max(500, Math.min(5000, eta - clock.now()));
        while (true) {
            pause(wait, deadline);
            wait = 1000; // MineSkin asks for at most one check a second per job
            Answer check = answer(request(BASE + "/v2/queue/" + segment(job), key.trim(), null, null));
            if (check.status() == 429) {
                wait = Math.max(1000, retryAfter(check));
                continue;
            }
            if (check.status() == 404) throw new SkinException("MineSkin lost track of the skin it was making; try again.");
            if (check.status() != 200) throw failure(check);
            String status = Json.string(check.json(), "job", "status");
            if ("failed".equals(status)) throw failure(check);
            if ("completed".equals(status)) {
                Signed done = skinOf(check.json());
                if (done != null) return done;
                String result = Json.string(check.json(), "job", "result");
                if (result != null && !result.isBlank() && result.length() <= 200) {
                    Answer skin = answer(request(BASE + "/v2/skins/" + segment(result), key.trim(), null, null));
                    if (skin.status() == 200) {
                        done = skinOf(skin.json());
                        if (done != null) return done;
                    }
                }
                // Completed, but the skin is not there yet: check again.
            }
            // waiting, active (or processing), unknown: check again.
        }
    }

    /** An id as one path segment of a link. */
    private static String segment(String id) {
        return java.net.URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Queues the job, waiting out rate limits while there is time. */
    private synchronized Answer submit(String key, byte[] body, String contentType, long deadline) throws SkinException {
        while (true) {
            long now = clock.now();
            if (nextSubmit > now) {
                if (nextSubmit > deadline) throw busy(nextSubmit - now);
                pause(nextSubmit - now, deadline);
            }
            Answer answer = answer(request(BASE + "/v2/queue", key, body, contentType));
            long next = (long) Json.number(answer.json(), 0, "rateLimit", "next", "relative");
            if (next > 0) nextSubmit = clock.now() + Math.min(next, 120_000);
            if (answer.status() == 429) {
                long wait = retryAfter(answer);
                if (clock.now() + wait > deadline) throw busy(wait);
                pause(wait, deadline);
                continue;
            }
            if (answer.status() == 200 || answer.status() == 202) return answer;
            throw failure(answer);
        }
    }

    private Http.Response request(String url, String key, byte[] body, String contentType) throws SkinException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + key);
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "application/json");
        try {
            if (body == null) return http.get(url, headers, MAX_ANSWER);
            headers.put("Content-Type", contentType);
            return http.post(url, headers, body, MAX_ANSWER);
        } catch (IOException e) {
            throw new SkinException("Could not reach MineSkin (" + e.getMessage() + "); try again in a minute.", e);
        }
    }

    private void pause(long millis, long deadline) throws SkinException {
        if (clock.now() + millis > deadline) {
            throw new SkinException("MineSkin did not finish the skin in time (it may be busy); try again in a minute.");
        }
        try {
            clock.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("Stopped waiting for MineSkin (the server is shutting down?).", e);
        }
    }

    /** One answer: its HTTP status and JSON body. */
    record Answer(int status, Map<String, Object> json, Http.Response raw) {
    }

    static Answer answer(Http.Response response) throws SkinException {
        try {
            return new Answer(response.status(), Json.object(response.text()), response);
        } catch (IllegalArgumentException e) {
            if (response.status() == 429) return new Answer(429, Map.of(), response);
            throw new SkinException("MineSkin answered HTTP " + response.status() + " without details; try again later.");
        }
    }

    /** The signed property in an answer's "skin" (SkinInfo), or in the answer itself; null if none. */
    static Signed skinOf(Map<String, Object> json) {
        for (Object skin : new Object[]{Json.at(json, "skin"), json}) {
            String value = Json.string(skin, "texture", "data", "value");
            String signature = Json.string(skin, "texture", "data", "signature");
            if (value != null && !value.isEmpty() && signature != null && !signature.isEmpty()) return new Signed(value, signature);
        }
        return null;
    }

    /** How long to wait after a 429: rateLimit.next.relative, else Retry-After (seconds), else 2 s. */
    static long retryAfter(Answer answer) {
        long next = (long) Json.number(answer.json(), 0, "rateLimit", "next", "relative");
        if (next > 0) return Math.min(next, 120_000);
        String header = answer.raw() == null ? null : answer.raw().header("Retry-After");
        if (header != null) {
            try {
                return Math.min(120_000, Math.max(0, (long) (Double.parseDouble(header.trim()) * 1000)));
            } catch (NumberFormatException ignored) {
                // a date: fall through
            }
        }
        return 2000;
    }

    private static SkinException busy(long wait) {
        long seconds = Math.max(1, (wait + 999) / 1000);
        return new SkinException("MineSkin's limit for your key is used up for now; try again in " + seconds + " seconds.");
    }

    /** A message for people from an error answer (or a failed job): its first error's code and message. */
    static SkinException failure(Answer answer) {
        Object first = Json.list(answer.json(), "errors").stream().findFirst().orElse(null);
        String code = first == null ? null : Json.string(first, "code");
        String message = first == null ? null : Json.string(first, "message");
        if (message == null) message = Json.string(answer.json(), "error");
        if (code == null) code = Json.string(answer.json(), "errorCode");
        String details = message == null || message.isBlank() ? "" : " (" + message.trim() + ")";
        if (code == null) code = "";
        if (code.equals("rate_limit") || answer.status() == 429) return busy(retryAfter(answer));
        if (code.equals("invalid_api_key") || answer.status() == 401 || answer.status() == 403) {
            return new SkinException("MineSkin refused the key in skins.mineskin-key" + details
                    + ". Check it at https://account.mineskin.org/keys, then /npc reload.");
        }
        return switch (code) {
            case "invalid_image" -> new SkinException("MineSkin does not take that image as a skin" + details
                    + ". A skin is a 64x64 or 64x32 PNG.");
            case "invalid_image_url" -> new SkinException("MineSkin could not get a skin image from that link" + details
                    + ". Use a direct link to the .png file.");
            case "skin_change_failed" -> new SkinException("Mojang would not take that skin, so MineSkin could not sign it"
                    + details + ". Try again later or with another image.");
            case "missing_file", "invalid_content_type", "validation_error" -> new SkinException(
                    "MineSkin did not accept the request" + details + ".");
            default -> new SkinException("MineSkin could not make the skin" + details
                    + (code.isEmpty() ? " (HTTP " + answer.status() + ")" : "") + "; try again later.");
        };
    }
}
