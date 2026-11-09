package com.brainrot.controller.policy;

import java.time.Instant;
import java.util.List;

/**
 * What the enforcer is told to do (the {@code set_policy} message in shared/protocol.md).
 *
 * <p>"Solved today" is {@code locked = true} with {@code unlockedUntil} at the end of the day, not
 * {@code locked = false}: the unlock then ends by itself even if this program has crashed.
 *
 * @param unlockedUntil end of the current unlock, or null when nothing is unlocked
 */
public record Policy(boolean locked, Instant unlockedUntil, int closeWarningMinutes, boolean dryRun,
                     List<AppRule> apps) {
    public Policy {
        apps = List.copyOf(apps);
    }

    public boolean isUnlockedAt(Instant now) {
        return !locked || (unlockedUntil != null && now.isBefore(unlockedUntil));
    }
}
