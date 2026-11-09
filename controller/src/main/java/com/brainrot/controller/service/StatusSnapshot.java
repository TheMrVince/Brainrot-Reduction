package com.brainrot.controller.service;

import java.time.Instant;

/**
 * Everything a display needs to know, and nothing that changes every second. Equal snapshots mean
 * nothing worth redrawing happened.
 *
 * @param unlockedUntil     end of the current unlock; null while locked
 * @param leetCodeReachable null until the first check
 * @param enforcerReachable null until the first delivery attempt
 * @param lastCheckAt       when LeetCode last answered; null if it has not
 */
public record StatusSnapshot(
        Reason reason,
        Instant unlockedUntil,
        Boolean leetCodeReachable,
        Boolean enforcerReachable,
        Instant lastCheckAt,
        boolean dryRun) {

    /** Why the state is what it is. */
    public enum Reason { LOCKED, SOLVED_TODAY, EMERGENCY_UNLOCK }

    public boolean unlocked() {
        return reason != Reason.LOCKED;
    }
}
