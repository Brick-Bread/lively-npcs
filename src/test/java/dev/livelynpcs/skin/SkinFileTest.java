package dev.livelynpcs.skin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** skins.yml: written by the plugin, read by the plugin (YamlConfiguration) and by ExportCli (SkinFile's own reader). */
class SkinFileTest {

    private static final String A = "49c88354a0494b6b9f2108997660ee9c06a2a5c39fd4c9ff37ceeab983240655";
    /** Its key, 0123456789012345, would read as a number if it were not quoted. */
    private static final String NUMERIC = "0123456789012345aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static List<Skin> sample() {
        return List.of(
                new Skin("49c88354a0494b6b", A, TestSkins.value(A, false), TestSkins.signature(A), false, "player:Rancher"),
                new Skin("0123456789012345", NUMERIC, TestSkins.value(NUMERIC, true), TestSkins.signature(NUMERIC), true,
                        "url:https://example.com/a skin #1.png?x='y'&z=\"w\""));
    }

    @Test
    void writtenSkinsReadBackTheSame() {
        String text = SkinFile.write(sample());
        SkinFile.Read read = SkinFile.read(text);
        assertEquals(List.of(), read.problems());
        assertEquals(sortedByKey(sample()), read.skins());
    }

    @Test
    void theServersYamlReaderAgrees() throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(SkinFile.write(sample()));
        ConfigurationSection section = yaml.getConfigurationSection("skins");
        List<String> problems = new ArrayList<>();
        List<Skin> read = new ArrayList<>();
        for (String name : section.getKeys(false)) {
            read.add(SkinFile.entry(name, section.getConfigurationSection(name).getValues(false), problems));
        }
        assertEquals(List.of(), problems);
        assertEquals(sortedByKey(sample()), read);
        assertTrue(section.getKeys(false).contains("0123456789012345"), "a number-like key stays text");
    }

    @Test
    void readsWhatTheServersYamlWriterWrites() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Skin s : sample()) {
            String p = "skins." + s.key();
            yaml.set(p + ".texture", s.texture());
            yaml.set(p + ".value", s.value());
            yaml.set(p + ".signature", s.signature());
            yaml.set(p + ".slim", s.slim());
            yaml.set(p + ".source", s.source());
        }
        SkinFile.Read read = SkinFile.read(yaml.saveToString());
        assertEquals(List.of(), read.problems());
        assertEquals(sortedByKey(sample()), sortedByKey(read.skins()));
    }

    @Test
    void handWrittenYamlIsRead() {
        String text = """
                # my skins
                ---
                skins:
                    'abc':   # a wrong key: corrected from the value
                        texture: 49c88354a0494b6b9f2108997660ee9c06a2a5c39fd4c9ff37ceeab983240655
                        value: "%s"
                        signature: '%s'
                        slim: yes
                        source: 'it''s mine'
                    plain:
                        value: %s
                        signature: "%s"
                """.formatted(TestSkins.value(A, false), TestSkins.signature(A),
                TestSkins.value(NUMERIC, true).substring(0, 30) + "\n          " + TestSkins.value(NUMERIC, true).substring(30),
                TestSkins.signature(NUMERIC));
        SkinFile.Read read = SkinFile.read(text);
        assertEquals(2, read.skins().size());
        Skin first = read.skins().getFirst();
        assertEquals("49c88354a0494b6b", first.key());
        assertFalse(first.slim(), "slim comes from the signed value");
        assertEquals("it's mine", first.source());
        assertEquals(NUMERIC, read.skins().get(1).texture(), "a plain value folded over two lines");
        assertTrue(read.problems().stream().anyMatch(p -> p.contains("skins.abc") && p.contains("key")), read.problems().toString());
        assertTrue(read.problems().stream().anyMatch(p -> p.contains("slim")), read.problems().toString());
    }

    @Test
    void doubleQuotedTextRunsOverLinesAndKeepsEscapes() {
        Map<String, Object> root = SkinFile.Yaml.parse("a: \"one\n   two\n\n   three \\u00e9\\\"\"\nb: 'x\n  y'\nc:\nd: ~\n");
        assertEquals("one two\nthree é\"", root.get("a"));
        assertEquals("x y", root.get("b"));
        assertEquals(null, root.get("c"));
        assertTrue(root.containsKey("d"));
    }

    @Test
    void entriesThatCannotBeUsedAreSkippedWithAReason() {
        String text = "skins:\n  broken:\n    value: \"not a skin\"\n    signature: \"x\"\n  unsigned:\n    value: \""
                + TestSkins.value(A, false) + "\"\n  word: hello\n";
        SkinFile.Read read = SkinFile.read(text);
        assertEquals(List.of(), read.skins());
        assertEquals(3, read.problems().size(), read.problems().toString());
        assertEquals(List.of(), SkinFile.read("skins: {}\n").skins());
        assertEquals(List.of(), SkinFile.read("# nothing\n").skins());
    }

    @Test
    void yamlThisReaderDoesNotTakeIsRefusedWithItsLine() {
        IllegalArgumentException flow = assertThrows(IllegalArgumentException.class,
                () -> SkinFile.read("skins:\n  abc: {value: x, signature: y}\n"));
        assertTrue(flow.getMessage().startsWith("line 2:"), flow.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SkinFile.read("skins:\n  abc:\n    value: |\n      x\n"));
        assertThrows(IllegalArgumentException.class, () -> SkinFile.read("skins:\n\tabc: 1\n"));
        assertThrows(IllegalArgumentException.class, () -> SkinFile.read("skins:\n  a: \"never ends\n"));
        assertThrows(IllegalArgumentException.class, () -> SkinFile.read("skins:\n    a: 1\n  b: 2\n"));
    }

    private static List<Skin> sortedByKey(List<Skin> skins) {
        List<Skin> sorted = new ArrayList<>(skins);
        sorted.sort(java.util.Comparator.comparing(Skin::key));
        return sorted;
    }
}
