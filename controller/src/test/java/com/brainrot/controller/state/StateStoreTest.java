package com.brainrot.controller.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StateStoreTest {
    private static final Instant T = Instant.parse("2026-11-05T12:00:00Z");

    @Test
    void aMissingFileIsAFreshStart(@TempDir Path dir) throws IOException {
        State state = new StateStore(dir.resolve("state.json")).load();

        assertNull(state.lastSolvedAt());
        assertNull(state.emergencyUnlockUntil());
        assertTrue(state.emergencyUnlocks().isEmpty());
    }

    @Test
    void stateSurvivesARestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested/state.json");  // parent folders are created
        State saved = State.empty()
                .withSolved(T)
                .withEmergencyUnlock(T.minusSeconds(86400), T.minusSeconds(82800))
                .withEmergencyUnlock(T, T.plusSeconds(3600));
        new StateStore(file).save(saved);

        State loaded = new StateStore(file).load();

        assertEquals(saved, loaded);
        assertEquals(T.plusSeconds(3600), loaded.emergencyUnlockUntil());
        assertEquals(2, loaded.emergencyUnlocks().size());
    }

    @Test
    void savingReplacesThePreviousStateAndLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("state.json");
        StateStore store = new StateStore(file);
        store.save(State.empty().withSolved(T));
        store.save(State.empty().withSolved(T.plusSeconds(60)));

        assertEquals(T.plusSeconds(60), store.load().lastSolvedAt());
        assertFalse(Files.exists(dir.resolve("state.json.tmp")));
    }

    @Test
    void theFileIsPrivateToTheUser(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "Windows has no POSIX permissions; the user's profile folder is already private");
        Path file = dir.resolve("state.json");
        new StateStore(file).save(State.empty());

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
    }

    @Test
    void aDamagedFileIsAnErrorNotASilentReset(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("state.json");
        StateStore store = new StateStore(file);

        Files.writeString(file, "{ broken");
        assertTrue(assertThrows(IOException.class, store::load).getMessage().contains("not valid JSON"));

        Files.writeString(file, "[]");
        assertThrows(IOException.class, store::load);

        Files.writeString(file, "{\"lastSolvedAt\":\"yesterday\"}");
        assertTrue(assertThrows(IOException.class, store::load).getMessage().contains("lastSolvedAt"));

        Files.writeString(file, "{\"emergencyUnlocks\":5}");
        assertThrows(IOException.class, store::load);
    }

    @Test
    void lastSolvedNeverMovesBackwards() {
        State state = State.empty().withSolved(T);

        assertSame(state, state.withSolved(T.minusSeconds(1)));
        assertEquals(T.plusSeconds(1), state.withSolved(T.plusSeconds(1)).lastSolvedAt());
    }

    @Test
    void oldEmergencyUnlocksAreForgotten() {
        Instant ancient = T.minusSeconds(40L * 86400);
        State state = State.empty().withEmergencyUnlock(ancient, ancient.plusSeconds(3600)).withEmergencyUnlock(T, T.plusSeconds(3600));

        assertEquals(List.of(T), state.emergencyUnlocks());
    }
}
