package dev.livelynpcs.rig;

import dev.livelynpcs.anim.Animation;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The jar's preset animations, read straight from the test classpath. */
final class TestAnimations {

    private TestAnimations() {
    }

    static Animation preset(String id) {
        try (InputStream in = TestAnimations.class.getClassLoader().getResourceAsStream("animations/" + id + ".yml")) {
            if (in == null) throw new IllegalArgumentException("no preset " + id);
            Map<?, ?> map = new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<String> problems = new ArrayList<>();
            Animation a = Animation.fromMap(id, map, true, problems);
            if (!problems.isEmpty()) throw new IllegalStateException(problems.toString());
            return a;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The same animation, looping. */
    static Animation looping(Animation a) {
        return new Animation(a.id(), a.name(), a.length(), true, a.glance(), a.keyframes(), false);
    }
}
