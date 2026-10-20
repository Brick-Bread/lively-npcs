package dev.livelynpcs.pack;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.livelynpcs.pack.PackTracker.Action.NOTHING;
import static dev.livelynpcs.pack.PackTracker.Action.REMOVE;
import static dev.livelynpcs.pack.PackTracker.Action.REPLACE;
import static dev.livelynpcs.pack.PackTracker.Action.SEND;
import static dev.livelynpcs.pack.PackTracker.Result.CHANGED;
import static dev.livelynpcs.pack.PackTracker.Result.IGNORED;
import static dev.livelynpcs.pack.PackTracker.Result.NOTED;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DISCARDED;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DOWNLOADED;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_RELOAD;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.INVALID_URL;
import static org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who has which NPC models: own mode's versions, merged mode's server pack, quits and hot swaps. */
class PackTrackerTest {

    private static final UUID V1 = UUID.randomUUID(), V2 = UUID.randomUUID(), SERVER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static final long RUN = 1_760_900_000_000L;
    private static final String A = "aaaaaaaaaaaaaaaa", B = "bbbbbbbbbbbbbbbb", C = "cccccccccccccccc";

    /** Own mode, our version V1 holding skins A and B, offered to PLAYER. */
    private static PackTracker own() {
        PackTracker t = new PackTracker();
        t.policy(PackTracker.Mode.OWN, "");
        t.version(V1, Set.of(A, B));
        t.offered(PLAYER, V1);
        return t;
    }

    private static YamlConfiguration saved(PackTracker before, Map<UUID, Integer> sessions) {
        YamlConfiguration out = new YamlConfiguration();
        before.save(out, RUN, sessions);
        return YamlConfiguration.loadConfiguration(new StringReader(out.saveToString()));
    }

    @Test
    void modelsOnlyOnceOurPackHasLoaded() {
        PackTracker t = own();
        assertEquals(NOTED, t.answer(PLAYER, V1, ACCEPTED));
        assertEquals(NOTED, t.answer(PLAYER, V1, DOWNLOADED));
        assertFalse(t.hasModels(PLAYER, A));
        assertEquals(CHANGED, t.answer(PLAYER, V1, SUCCESSFULLY_LOADED));
        assertTrue(t.hasModels(PLAYER, A));
        assertTrue(t.hasModels(PLAYER, B));
        assertFalse(t.hasModels(PLAYER, C), "only the skins that version holds");
        assertEquals(Set.of(A, B), t.modelKeys(PLAYER));
        // The same answer again changes nothing.
        assertEquals(IGNORED, t.answer(PLAYER, V1, SUCCESSFULLY_LOADED));
        assertFalse(t.hasModels(UUID.randomUUID(), A));
    }

    @Test
    void everyFailureMeansNoModels() {
        for (Status failure : List.of(DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED)) {
            PackTracker t = own();
            assertEquals(NOTED, t.answer(PLAYER, V1, failure), failure.name());
            assertEquals(CHANGED, t.answer(PLAYER, V1, SUCCESSFULLY_LOADED), failure.name());
            assertEquals(CHANGED, t.answer(PLAYER, V1, failure), failure.name());
            assertFalse(t.hasModels(PLAYER, A), failure.name());
        }
    }

    @Test
    void inOwnModeOnlyOurPacksCount() {
        PackTracker t = own();
        t.pushed(PLAYER, SERVER, "https://example.com/server.zip");
        t.answer(PLAYER, SERVER, SUCCESSFULLY_LOADED);
        assertFalse(t.hasModels(PLAYER, A));
        assertFalse(t.hasPack(PLAYER));
        assertEquals(IGNORED, t.answer(PLAYER, null, SUCCESSFULLY_LOADED));
    }

    @Test
    void aNewVersionReplacesTheOld() {
        PackTracker t = own();
        t.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        assertEquals(NOTHING, t.plan(PLAYER, V1));
        t.version(V2, Set.of(A, B, C));
        assertEquals(REPLACE, t.plan(PLAYER, V2));
        // PackManager takes V1 away (the game answers nothing for that) and offers V2.
        assertTrue(t.popped(PLAYER, V1));
        t.offered(PLAYER, V2);
        assertFalse(t.hasModels(PLAYER, A), "nothing until the new one loads");
        assertEquals(NOTHING, t.plan(PLAYER, V2));
        // A late answer about the old version does not bring it back... it is not offered any more,
        // but our own versions still count if the game says it has one.
        assertEquals(CHANGED, t.answer(PLAYER, V2, SUCCESSFULLY_LOADED));
        assertEquals(Set.of(A, B, C), t.modelKeys(PLAYER));
        t.prune(V2);
        assertTrue(t.ours(V2));
        assertFalse(t.ours(V1), "an old version nobody has is forgotten");
    }

    @Test
    void plans() {
        PackTracker t = new PackTracker();
        t.version(V1, Set.of(A));
        assertEquals(SEND, t.plan(PLAYER, V1), "never offered");
        assertEquals(NOTHING, t.plan(PLAYER, null));
        t.offered(PLAYER, V1);
        t.answer(PLAYER, V1, DECLINED);
        assertEquals(NOTHING, t.plan(PLAYER, V1), "said no: not asked again");
        assertEquals(REMOVE, t.plan(PLAYER, null), "no pack now: the old one goes");
        assertEquals(REPLACE, t.plan(PLAYER, V2));
    }

    @Test
    void aPackOfferedAgainCountsOnlyWhenItLoadsAgain() {
        PackTracker t = own();
        t.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        t.pushed(PLAYER, V1, "http://host/pack/x.zip");
        assertFalse(t.hasModels(PLAYER, A));
        assertEquals(CHANGED, t.answer(PLAYER, V1, SUCCESSFULLY_LOADED), "not ignored as a repeat");
        assertTrue(t.hasModels(PLAYER, A));
    }

    @Test
    void takenAwayPacksAreGone() {
        PackTracker t = own();
        t.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        assertFalse(t.popped(PLAYER, OTHER), "someone else's pack");
        assertTrue(t.hasModels(PLAYER, A));
        assertTrue(t.popped(PLAYER, null), "every pack taken away, ours too");
        assertFalse(t.hasModels(PLAYER, A));
        assertNull(t.offered(PLAYER));
        assertEquals(SEND, t.plan(PLAYER, V1), "so it is offered again");
        assertFalse(t.popped(UUID.randomUUID(), V1));
    }

    @Test
    void mergedModeCountsTheServersPackWithTheConfirmedSkins() {
        PackTracker t = new PackTracker();
        t.policy(PackTracker.Mode.MERGED, "");
        t.merged(Set.of(A));
        t.pushed(PLAYER, SERVER, "https://github.com/Brick-Bread/raiseamob-pack/raw/season1/pack.zip");
        assertFalse(t.hasModels(PLAYER, A));
        assertEquals(CHANGED, t.answer(PLAYER, SERVER, SUCCESSFULLY_LOADED));
        assertTrue(t.hasModels(PLAYER, A));
        assertFalse(t.hasModels(PLAYER, B), "added after the last /npc pack merged");
        t.merged(Set.of(A, B));
        assertTrue(t.hasModels(PLAYER, B));
        assertTrue(t.hasPack(PLAYER));
        // An answer seen in the configuration phase (no link known) counts too when there is no filter.
        UUID early = UUID.randomUUID();
        t.answer(early, OTHER, SUCCESSFULLY_LOADED);
        assertTrue(t.hasModels(early, A));
    }

    @Test
    void mergedModeWithAFilterCountsOnlyThatPack() {
        PackTracker t = new PackTracker();
        t.policy(PackTracker.Mode.MERGED, "raiseamob-pack");
        t.merged(Set.of(A));
        t.pushed(PLAYER, OTHER, "https://cdn.example.com/other.zip");
        t.answer(PLAYER, OTHER, SUCCESSFULLY_LOADED);
        assertFalse(t.hasModels(PLAYER, A), "another plugin's pack");
        t.answer(PLAYER, SERVER, SUCCESSFULLY_LOADED);
        assertFalse(t.hasModels(PLAYER, A), "link never seen");
        t.pushed(PLAYER, SERVER, "https://github.com/Brick-Bread/raiseamob-pack/raw/season1/pack.zip");
        t.answer(PLAYER, SERVER, SUCCESSFULLY_LOADED);
        assertTrue(t.hasModels(PLAYER, A));
        // Our own pack (from own mode) never counts as the server's.
        t.version(V1, Set.of(A));
        t.pushed(PLAYER, V1, "https://raiseamob-pack.example/x");
        t.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        t.popped(PLAYER, SERVER);
        assertFalse(t.hasModels(PLAYER, A));
    }

    @Test
    void switchingModesChangesWhatCounts() {
        PackTracker t = own();
        t.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        t.answer(PLAYER, SERVER, SUCCESSFULLY_LOADED);
        t.merged(Set.of(C));
        assertEquals(Set.of(A, B), t.modelKeys(PLAYER));
        t.policy(PackTracker.Mode.MERGED, "");
        assertEquals(Set.of(C), t.modelKeys(PLAYER));
    }

    @Test
    void quittingForgetsThePlayer() {
        PackTracker t = own();
        t.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        t.forget(PLAYER);
        assertFalse(t.hasModels(PLAYER, A));
        assertNull(t.offered(PLAYER));
        assertEquals(SEND, t.plan(PLAYER, V1), "their next session is offered it again");
        assertEquals(CHANGED, t.answer(PLAYER, V1, SUCCESSFULLY_LOADED));
    }

    @Test
    void aHotSwapKeepsEveryoneInTheSameSession() {
        PackTracker before = own();
        UUID decliner = UUID.randomUUID(), rejoined = UUID.randomUUID(), loading = UUID.randomUUID();
        before.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        before.pushed(PLAYER, SERVER, "https://example.com/server.zip");
        before.answer(PLAYER, SERVER, SUCCESSFULLY_LOADED);
        before.offered(decliner, V1);
        before.answer(decliner, V1, DECLINED);
        before.offered(rejoined, V1);
        before.answer(rejoined, V1, SUCCESSFULLY_LOADED);
        before.offered(loading, V1);
        before.answer(loading, V1, ACCEPTED);
        YamlConfiguration saved = saved(before, Map.of(PLAYER, 10, decliner, 11, rejoined, 12, loading, 13));

        // The next copy has not built or offered anything yet: it knows V1 only from the saved state.
        PackTracker after = new PackTracker();
        after.policy(PackTracker.Mode.OWN, "");
        assertTrue(after.resume(saved, RUN, PLAYER, 10));
        assertTrue(after.hasModels(PLAYER, A), "models carry on without a flicker");
        assertEquals(Set.of(A, B), after.modelKeys(PLAYER));
        assertEquals(IGNORED, after.answer(PLAYER, V1, SUCCESSFULLY_LOADED), "their game repeating itself is not news");
        assertEquals(NOTHING, after.plan(PLAYER, V1));
        assertEquals(REPLACE, after.plan(PLAYER, V2), "a pack changed during the swap: the old one goes, the new one comes");
        // Said no: not asked again.
        assertTrue(after.resume(saved, RUN, decliner, 11));
        assertEquals(NOTHING, after.plan(decliner, V1));
        assertFalse(after.hasModels(decliner, A));
        // Still loading during the swap: their final answer still counts.
        assertTrue(after.resume(saved, RUN, loading, 13));
        assertEquals(CHANGED, after.answer(loading, V1, SUCCESSFULLY_LOADED));
        assertTrue(after.hasModels(loading, B));
        // Left and came back during the swap (a new entity id): their game no longer has it.
        assertFalse(after.resume(saved, RUN, rejoined, 40));
        assertEquals(SEND, after.plan(rejoined, V1));
        assertFalse(after.hasModels(rejoined, A));
        // Joined during the swap.
        assertFalse(after.resume(saved, RUN, UUID.randomUUID(), 41));
        // The server pack they had is kept too, with its link, for merged mode.
        after.policy(PackTracker.Mode.MERGED, "server.zip");
        after.merged(Set.of(C));
        assertTrue(after.hasModels(PLAYER, C));
    }

    @Test
    void savedStateFromAnotherRunOrNoneIsNotUsed() {
        PackTracker before = own();
        before.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        YamlConfiguration saved = saved(before, Map.of(PLAYER, 10));
        PackTracker restarted = new PackTracker();
        assertFalse(restarted.resume(saved, RUN + 1, PLAYER, 10), "entity ids start over with every run");
        assertFalse(restarted.hasModels(PLAYER, A));
        assertFalse(restarted.resume(null, RUN, PLAYER, 10));
    }

    @Test
    void theSavedStateReadsBackAsText() {
        PackTracker before = own();
        before.answer(PLAYER, V1, SUCCESSFULLY_LOADED);
        YamlConfiguration saved = saved(before, Map.of(PLAYER, 10));
        assertEquals(RUN, saved.getLong("run"));
        assertEquals(V1.toString(), saved.getString("players." + PLAYER + ".offered"));
        assertEquals(List.of(A, B), saved.getStringList("versions." + V1));
        assertTrue(saved.getBoolean("players." + PLAYER + ".packs." + V1 + ".loaded"));
    }
}
