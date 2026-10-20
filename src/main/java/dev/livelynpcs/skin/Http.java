package dev.livelynpcs.skin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The few web requests Lively NPCs makes itself (Mojang's profile lookups, MineSkin and
 * textures.minecraft.net), behind an interface so tests can answer them without a network. JDK only.
 */
public interface Http {

    /** An answer: status, headers (names in lowercase, values joined with ", ") and body. */
    record Response(int status, Map<String, String> headers, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    Response get(String url, Map<String, String> headers, int maxBytes) throws IOException;

    Response post(String url, Map<String, String> headers, byte[] body, int maxBytes) throws IOException;

    /** The JDK's HTTP client: 10 s to connect, 20 s for an answer, redirects followed. */
    static Http jdk() {
        return new Http() {
            @Override
            public Response get(String url, Map<String, String> headers, int maxBytes) throws IOException {
                return send(HttpRequest.newBuilder(uri(url)).GET(), headers, maxBytes);
            }

            @Override
            public Response post(String url, Map<String, String> headers, byte[] body, int maxBytes) throws IOException {
                return send(HttpRequest.newBuilder(uri(url)).POST(HttpRequest.BodyPublishers.ofByteArray(body)), headers, maxBytes);
            }

            private Response send(HttpRequest.Builder request, Map<String, String> headers, int maxBytes) throws IOException {
                request.timeout(Duration.ofSeconds(20));
                headers.forEach(request::header);
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL).build()) {
                    HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                    byte[] body;
                    try (InputStream in = response.body()) {
                        body = readUpTo(in, maxBytes);
                    }
                    Map<String, String> lower = new HashMap<>();
                    for (Map.Entry<String, List<String>> e : response.headers().map().entrySet()) {
                        lower.put(e.getKey().toLowerCase(Locale.ROOT), String.join(", ", e.getValue()));
                    }
                    return new Response(response.statusCode(), lower, body);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }

            private URI uri(String url) throws IOException {
                try {
                    return URI.create(url);
                } catch (IllegalArgumentException e) {
                    throw new IOException("bad link " + url, e);
                }
            }
        };
    }

    private static byte[] readUpTo(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > maxBytes) throw new IOException("the answer is bigger than " + maxBytes + " bytes");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
