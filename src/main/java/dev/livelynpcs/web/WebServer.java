package dev.livelynpcs.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.livelynpcs.LivelyNpcs;
import dev.livelynpcs.Settings;
import org.bukkit.Bukkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lively NPCs' own web server (web: in config.yml), on the JDK's built-in HTTP server: the editor's
 * page and files under /editor/ (from the jar's web/ folder), its JSON API under /api/ (EditorApi),
 * and the pack under /pack/ in own/builtin mode (PackManager). See DESIGN.md "Web".
 *
 * <p>Handlers run on the server's own threads: anything touching the game hops to the main thread.
 * The port and address are read once, on start: changing them needs a restart (or a hot swap).
 */
public final class WebServer {

    /** An editor login: made by /npc editor for one player, valid until expiresAt (epoch millis). */
    public record Session(String token, UUID player, long expiresAt) {
        public boolean live() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    /** The "player" of an editor session opened from the console: no one in game, so no live preview. */
    public static final UUID CONSOLE = new UUID(0, 0);

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
    /** Requests bigger than this are refused (an uploaded skin is a few kilobytes; animations far less). */
    public static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "mjs", "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8",
            "json", "application/json; charset=utf-8", "png", "image/png", "svg", "image/svg+xml",
            "woff2", "font/woff2", "ico", "image/x-icon", "txt", "text/plain; charset=utf-8");

    /** Text files worth compressing: the editor's scripts are most of what a first visit downloads. */
    private static final Set<String> COMPRESS = Set.of("html", "js", "mjs", "css", "json", "svg", "txt");

    /** One of the editor's files, read from the jar once: as it is, gzipped (or null), and its ETag. */
    private record Static(byte[] raw, byte[] gzip, String etag, String type) { }

    private final LivelyNpcs plugin;
    private final Map<String, Static> files = new ConcurrentHashMap<>();
    /** Link header for the page: every script, so the browser fetches them together, not one import at a time. */
    private volatile String preloads;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private HttpServer server;
    private ExecutorService threads;
    private int port;

    public WebServer(LivelyNpcs plugin) {
        this.plugin = plugin;
    }

    /** Starts listening on web.bind:web.port (if web.enabled). Throws if the port cannot be had. */
    public void start() throws IOException {
        Settings s = plugin.settings();
        port = s.webPort;
        // Every interface, IPv4 and IPv6 alike: a browser that tries IPv6 first (as "localhost" does
        // on Windows) would otherwise wait for the refusal on each new connection before falling back.
        boolean everywhere = s.webBind.isEmpty() || s.webBind.equals("0.0.0.0") || s.webBind.equals("::");
        server = HttpServer.create(everywhere ? new InetSocketAddress(port) : new InetSocketAddress(s.webBind, port), 0);
        AtomicInteger n = new AtomicInteger();
        threads = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "LivelyNPCs-web-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(threads);
        server.createContext("/editor", this::editorFile);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (exchange.getRequestURI().getPath().equals("/")) {
                    exchange.getResponseHeaders().set("Location", "/editor/");
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    bytes(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (threads != null) threads.shutdownNow();
        server = null;
        threads = null;
        sessions.clear();
    }

    /** Whether it is listening. */
    public boolean running() {
        return server != null;
    }

    /** Handles every request whose path starts with prefix (e.g. "/api/"). Call after start(). */
    public void handle(String prefix, HttpHandler handler) {
        if (server == null) throw new IllegalStateException("the web server is not running");
        server.createContext(prefix, exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                handler.handle(exchange);
            } catch (Exception e) {
                plugin.getLogger().warning("Web " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                        + " failed: " + e);
                try {
                    json(exchange, 500, Map.of("error", "Something went wrong on the server: " + e.getMessage()));
                } catch (IOException | RuntimeException ignored) {
                    // headers may already be gone
                }
            }
        });
    }

    /** How browsers reach this server, e.g. "http://play.example.com:8193" (no trailing slash). */
    public String baseUrl() {
        String address = plugin.settings().webPublicAddress;
        if (address.isEmpty()) {
            String ip = Bukkit.getIp();
            // 127.0.0.1 rather than "localhost": the name may be tried over IPv6 first, which is slower.
            address = (ip == null || ip.isBlank() ? "127.0.0.1" : ip) + ":" + port;
        }
        if (!address.startsWith("http://") && !address.startsWith("https://")) address = "http://" + address;
        return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }

    /** A new editor session for this player, valid for web.editor-minutes. */
    public Session newSession(UUID player) {
        sessions.values().removeIf(s -> !s.live());
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session = new Session(token, player, System.currentTimeMillis() + plugin.settings().editorMinutes * 60_000L);
        sessions.put(token, session);
        return session;
    }

    /** The live session named by this request's "Authorization: Bearer &lt;token&gt;" header, or null. */
    public Session session(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        Session s = sessions.get(header.substring(7).trim());
        if (s == null) return null;
        if (!s.live()) {
            sessions.remove(s.token());
            return null;
        }
        return s;
    }

    /** Sends body as JSON (Gson) with this status. */
    public static void json(HttpExchange exchange, int status, Object body) throws IOException {
        bytes(exchange, status, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    /** Reads the request body as JSON into type (Gson); throws IOException on bad JSON. */
    public static <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        String text = new String(body(exchange, MAX_BODY), StandardCharsets.UTF_8);
        try {
            T value = GSON.fromJson(text, type);
            if (value == null) throw new IOException("empty request");
            return value;
        } catch (JsonParseException e) {
            throw new IOException("not JSON: " + e.getMessage(), e);
        }
    }

    /** The shared Gson (HTML characters left as they are). */
    public static Gson gson() {
        return GSON;
    }

    /** Sends raw bytes with this content type and status. */
    public static void bytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /** Reads the whole request body (up to limit bytes; more is an IOException). */
    public static byte[] body(HttpExchange exchange, int limit) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > limit) throw new IOException("request bigger than " + limit + " bytes");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** /editor/... : the editor's files from the jar's web/ folder; /editor and /editor/ are its page. */
    private void editorFile(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/editor")) {
                exchange.getResponseHeaders().set("Location", "/editor/");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            String file = path.substring("/editor/".length());
            if (file.isEmpty()) file = "index.html";
            if (!file.matches("[A-Za-z0-9_./-]+") || file.contains("..") || file.startsWith("/")) {
                bytes(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Static f = files.computeIfAbsent(file, this::load);
            if (f.raw() == null) {
                bytes(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            var headers = exchange.getResponseHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            // Scripts and styles are checked again on each visit but only downloaded when they changed
            // (ETag); the vendored libraries are kept for a day.
            headers.set("Cache-Control", file.startsWith("vendor/") ? "max-age=86400" : "no-cache");
            headers.set("ETag", f.etag());
            if (file.equals("index.html")) {
                headers.set("Content-Security-Policy",
                        "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'");
                headers.set("Referrer-Policy", "no-referrer");
                String links = preloads();
                if (!links.isEmpty()) headers.set("Link", links);
            }
            if (f.etag().equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            String accepts = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            if (f.gzip() != null && accepts != null && accepts.contains("gzip")) {
                headers.set("Content-Encoding", "gzip");
                headers.set("Vary", "Accept-Encoding");
                bytes(exchange, 200, f.type(), f.gzip());
            } else {
                bytes(exchange, 200, f.type(), f.raw());
            }
        }
    }

    /** Reads one of the editor's files from the jar (raw null when there is none), compressed if it is text. */
    private Static load(String file) {
        try (InputStream in = plugin.getResource("web/" + file)) {
            if (in == null) return new Static(null, null, "", "");
            byte[] raw = in.readAllBytes();
            String ext = file.contains(".") ? file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            byte[] gzip = null;
            if (COMPRESS.contains(ext) && raw.length > 512) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3);
                try (GZIPOutputStream z = new GZIPOutputStream(out)) {
                    z.write(raw);
                }
                gzip = out.toByteArray();
            }
            String etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw), 0, 8) + "\"";
            return new Static(raw, gzip, etag, TYPES.getOrDefault(ext, "application/octet-stream"));
        } catch (IOException | NoSuchAlgorithmException e) {
            plugin.getLogger().warning("Could not read the editor's " + file + ": " + e.getMessage());
            return new Static(null, null, "", "");
        }
    }

    /** "</editor/js/a.js>; rel=modulepreload, ..." for every script under web/ in the plugin's jar. */
    private String preloads() {
        String links = preloads;
        if (links != null) return links;
        List<String> scripts = new ArrayList<>();
        try {
            Path jar = Path.of(LivelyNpcs.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(jar)) {
                Path web = jar.resolve("web");
                if (Files.isDirectory(web)) {
                    try (Stream<Path> walk = Files.walk(web)) {
                        walk.filter(p -> p.toString().endsWith(".js"))
                                .forEach(p -> scripts.add(web.relativize(p).toString().replace('\\', '/')));
                    }
                }
            } else {
                try (JarFile zip = new JarFile(jar.toFile())) {
                    for (Enumeration<JarEntry> e = zip.entries(); e.hasMoreElements(); ) {
                        String name = e.nextElement().getName();
                        if (name.startsWith("web/") && name.endsWith(".js")) scripts.add(name.substring(4));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("No script preloads: " + e);
        }
        scripts.sort(null);
        StringBuilder out = new StringBuilder();
        for (String script : scripts) {
            if (!out.isEmpty()) out.append(", ");
            out.append("</editor/").append(script).append(">; rel=modulepreload");
        }
        preloads = out.toString();
        return preloads;
    }
}
