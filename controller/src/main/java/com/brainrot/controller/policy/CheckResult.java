package com.brainrot.controller.policy;

import com.brainrot.controller.leetcode.AcceptedSubmission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The outcome of asking LeetCode what the user has solved. */
public sealed interface CheckResult {

    /** LeetCode answered; {@code newestAccepted} is empty if the user has no recent accepted submissions. */
    record Reached(Optional<Instant> newestAccepted) implements CheckResult {
        public static Reached from(List<AcceptedSubmission> submissions) {
            return new Reached(submissions.stream().map(AcceptedSubmission::solvedAt).max(Instant::compareTo));
        }
    }

    /** No usable answer (offline, throttled, profile private). The engine then relies on what it remembers. */
    record Unreachable(String reason) implements CheckResult {}
}
