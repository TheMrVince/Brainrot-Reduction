package com.brainrot.controller.state;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What the controller remembers between runs. Immutable; the {@code with...} methods return updated copies.
 *
 * @param lastSolvedAt           newest accepted submission seen, or null
 * @param emergencyUnlocks       when each emergency unlock was used (recent ones only)
 * @param emergencyUnlockUntil   end of the running emergency unlock, or null
 */
public record State(Instant lastSolvedAt, List<Instant> emergencyUnlocks, Instant emergencyUnlockUntil) {
    private static final Duration KEEP_HISTORY = Duration.ofDays(30);

    public State {
        emergencyUnlocks = emergencyUnlocks == null ? List.of() : List.copyOf(emergencyUnlocks);
    }

    public static State empty() {
        return new State(null, List.of(), null);
    }

    /** Never moves {@code lastSolvedAt} backwards. */
    public State withSolved(Instant solvedAt) {
        if (lastSolvedAt != null && !solvedAt.isAfter(lastSolvedAt)) return this;
        return new State(solvedAt, emergencyUnlocks, emergencyUnlockUntil);
    }

    public State withEmergencyUnlock(Instant usedAt, Instant until) {
        List<Instant> history = new ArrayList<>();
        for (Instant earlier : emergencyUnlocks) {
            if (earlier.isAfter(usedAt.minus(KEEP_HISTORY))) history.add(earlier);
        }
        history.add(usedAt);
        return new State(lastSolvedAt, history, until);
    }
}
