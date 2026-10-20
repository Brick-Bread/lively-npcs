package dev.livelynpcs.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor page works under its Content-Security-Policy (default-src 'self'): no inline scripts or
 * import maps, nothing loaded from outside, every module import a file that is there, and every file
 * a type the web server serves.
 */
class WebResourcesTest {

    private static final Path WEB = Path.of("src/main/resources/web");
    private static final Pattern IMPORT = Pattern.compile("(?:import|export)\\s*(?:[^'\";]*?\\bfrom\\s*)?['\"]([^'\"]+)['\"]|import\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    private static List<Path> files() throws IOException {
        try (Stream<Path> s = Files.walk(WEB)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void pageHasNoInlineScriptAndLoadsNothingFromOutside() throws IOException {
        String html = Files.readString(WEB.resolve("index.html"), StandardCharsets.UTF_8);
        Matcher scripts = Pattern.compile("<script([^>]*)>(.*?)</script>", Pattern.DOTALL).matcher(html);
        int count = 0;
        while (scripts.find()) {
            count++;
            assertTrue(scripts.group(1).contains("src="), "every script is a file");
            assertEquals("", scripts.group(2).trim(), "no inline script (the CSP forbids it)");
            assertFalse(scripts.group(1).contains("importmap"), "no import map (the CSP forbids it)");
        }
        assertEquals(1, count, "one module script starts the page");
        for (Path f : files()) {
            String name = f.getFileName().toString();
            if (!(name.endsWith(".html") || name.endsWith(".css") || (name.endsWith(".js") && !f.toString().contains("vendor")))) continue;
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Matcher url = Pattern.compile("(?:src|href)\\s*=\\s*['\"](https?:)?//|url\\(\\s*['\"]?https?://|@import").matcher(text);
            assertFalse(url.find(), f + " loads something from outside");
        }
    }

    @Test
    void everyImportIsAFileThatIsThere() throws IOException {
        List<String> missing = new ArrayList<>();
        int imports = 0;
        for (Path f : files()) {
            if (!f.toString().endsWith(".js")) continue;
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = IMPORT.matcher(text);
            while (m.find()) {
                String spec = m.group(1) != null ? m.group(1) : m.group(2);
                imports++;
                if (!spec.startsWith("./") && !spec.startsWith("../") && !spec.startsWith("/editor/")) {
                    missing.add(f + " imports '" + spec + "' (only relative paths work without an import map)");
                    continue;
                }
                Path target = spec.startsWith("/editor/") ? WEB.resolve(spec.substring("/editor/".length())) : f.getParent().resolve(spec).normalize();
                if (!Files.isRegularFile(target)) missing.add(f + " imports '" + spec + "', which is not there");
            }
        }
        assertTrue(imports > 20, "found the imports");
        assertEquals(List.of(), missing);
    }

    @Test
    void everyFileIsServedWithItsType() throws IOException {
        for (Path f : files()) {
            String rel = WEB.relativize(f).toString().replace('\\', '/');
            assertTrue(rel.matches("[A-Za-z0-9_./-]+") && !rel.contains(".."), rel + ": the web server only serves plain names");
            String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1) : "";
            boolean typed = List.of("html", "js", "css", "svg", "png", "json", "txt", "woff2", "ico").contains(ext) || rel.endsWith("LICENSE");
            assertTrue(typed, rel + " has no content type in WebServer");
        }
        assertTrue(Files.isRegularFile(WEB.resolve("vendor/three/LICENSE")), "three.js's licence ships with it");
    }
}
