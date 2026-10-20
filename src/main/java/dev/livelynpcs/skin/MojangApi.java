package dev.livelynpcs.skin;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A player's current skin from Mojang, signed (no key needed): their id from
 * api.mojang.com/users/profiles/minecraft/&lt;name&gt;, then the signed textures property from
 * sessionserver.mojang.com/session/minecraft/profile/&lt;id&gt;?unsigned=false. Blocking: call it off the
 * main thread. JDK only.
 */
public final class MojangApi {

    /** A signed textures property, and the name it came from. */
    public record Signed(String value, String signature, String name) {
    }

    /** What Mojang accepts as a name (old accounts can be shorter than 3). */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{32}");
    static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    static final String SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private final Http http;
    private final String userAgent;

    public MojangApi(Http http, String userAgent) {
        this.http = http;
        this.userAgent = userAgent;
    }

    /** That player's skin, signed by Mojang; SkinException (for people) when it cannot be had. */
    public Signed lookup(String name) throws SkinException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new SkinException("'" + name + "' is not a Minecraft player name (1-16 letters, digits and _)");
        }
        Http.Response first = get(PROFILE_URL + name);
        if (first.status() == 204 || first.status() == 404) throw new SkinException("No Minecraft player is called " + name + ".");
        failOnError(first);
        String[] idAndName = parseProfile(first.text());
        Http.Response second = get(SESSION_URL + idAndName[0] + "?unsigned=false");
        if (second.status() == 204 || second.status() == 404) {
            throw new SkinException("Mojang has no profile for " + idAndName[1] + " right now; try again in a minute.");
        }
        failOnError(second);
        String[] property = parseTextures(second.text(), idAndName[1]);
        try {
            TextureValue.parse(property[0]);
        } catch (IllegalArgumentException e) {
            if (TextureValue.withoutSkin(property[0])) {
                throw new SkinException(idAndName[1] + " has no skin of their own (they use one of the game's default skins).");
            }
            throw new SkinException("Mojang sent " + idAndName[1] + "'s skin in a form Lively NPCs cannot read (" + e.getMessage() + ").");
        }
        return new Signed(property[0], property[1], idAndName[1]);
    }

    private Http.Response get(String url) throws SkinException {
        try {
            return http.get(url, Map.of("User-Agent", userAgent, "Accept", "application/json"), 64 * 1024);
        } catch (IOException e) {
            throw new SkinException("Could not reach Mojang (" + e.getMessage() + "); try again in a minute.", e);
        }
    }

    private static void failOnError(Http.Response response) throws SkinException {
        if (response.status() == 429) {
            throw new SkinException("Mojang is limiting skin lookups right now; try again in a minute.");
        }
        if (response.status() != 200) {
            throw new SkinException("Mojang answered HTTP " + response.status() + "; try again in a minute.");
        }
    }

    /** {id, name} from api.mojang.com's answer {"id": "&lt;32 hex&gt;", "name": "&lt;Name&gt;"}. */
    static String[] parseProfile(String json) throws SkinException {
        Map<String, Object> body;
        try {
            body = Json.object(json);
        } catch (IllegalArgumentException e) {
            throw new SkinException("Mojang answered with something unexpected (" + e.getMessage() + ").");
        }
        String id = Json.string(body, "id");
        String name = Json.string(body, "name");
        if (id == null || !ID.matcher(id).matches()) throw new SkinException("Mojang answered without a player id.");
        return new String[]{id.toLowerCase(java.util.Locale.ROOT), name == null ? "" : name};
    }

    /**
     * {value, signature} of the "textures" property in the session server's answer
     * {"id": ..., "name": ..., "properties": [{"name": "textures", "value": ..., "signature": ...}]}.
     */
    static String[] parseTextures(String json, String name) throws SkinException {
        Map<String, Object> body;
        try {
            body = Json.object(json);
        } catch (IllegalArgumentException e) {
            throw new SkinException("Mojang answered with something unexpected (" + e.getMessage() + ").");
        }
        for (Object property : Json.list(body, "properties")) {
            if (!"textures".equals(Json.string(property, "name"))) continue;
            String value = Json.string(property, "value");
            String signature = Json.string(property, "signature");
            if (value == null || value.isEmpty()) break;
            if (signature == null || signature.isEmpty()) throw new SkinException("Mojang sent " + name + "'s skin unsigned; try again.");
            return new String[]{value, signature};
        }
        throw new SkinException(name + " has no skin of their own (they use one of the game's default skins).");
    }
}
