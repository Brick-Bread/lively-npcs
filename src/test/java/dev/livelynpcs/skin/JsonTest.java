package dev.livelynpcs.skin;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void readsEveryKindOfValue() {
        Map<String, Object> o = Json.object("{\"a\": [1, 2.5, -3e2], \"b\": {\"c\": true, \"d\": null}, \"e\": \"x\\\"\\u00e9\\n\"}");
        assertEquals(List.of(1.0, 2.5, -300.0), o.get("a"));
        assertEquals(Boolean.TRUE, Json.at(o, "b", "c"));
        assertNull(Json.at(o, "b", "d"));
        assertEquals("x\"é\n", o.get("e"));
        assertEquals(2.5, Json.number(o, 0, "nope"), 2.5);
        assertTrue(Json.list(o, "missing").isEmpty());
    }

    @Test
    void refusesWhatIsNotJson() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"open"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("[1]"));
    }

    @Test
    void writesWholeNumbersWithoutAPoint() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("from", List.of(4.0, -0.5, 10.25));
        o.put("n", 8);
        o.put("s", "a\"b\\c\n");
        assertEquals("{\"from\":[4,-0.5,10.25],\"n\":8,\"s\":\"a\\\"b\\\\c\\n\"}", Json.write(o));
        assertEquals("{\n  \"from\": [4, -0.5, 10.25],\n  \"n\": 8,\n  \"s\": \"a\\\"b\\\\c\\n\"\n}\n", Json.pretty(o));
        // and back
        assertEquals(Json.parse(Json.write(o)), Json.parse(Json.pretty(o)));
    }
}
