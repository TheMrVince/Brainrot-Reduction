package com.brainrot.controller.policy;

import com.brainrot.controller.config.Config;
import com.brainrot.controller.state.State;
import com.brainrot.controller.time.DayClock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The decision-maker: facts in, policy out. No network, files or clocks of its own, so every
 * rule can be tested by handing it a state and a moment in time.
 *
 * <ul>
 *   <li>Locked unless today's problem is solved or an emergency unlock is running.</li>
 *   <li>Offline means "go by what was remembered": a solve already seen today stays valid, and
 *       with no such memory the answer is locked (fail closed).</li>
 *   <li>Every unlock has an end time, so a stale policy re-locks without anyone's help.</li>
 * </ul>
 */
public final class PolicyEngine {
    private static final Duration ALLOWANCE_WINDOW = Duration.ofDays(7);

    private final Config config;
    private final DayClock clock;

    public PolicyEngine(Config config, DayClock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** Folds a LeetCode answer into the remembered state. */
    public State observe(State state, CheckResult result) {
        if (result instanceof CheckResult.Reached reached && reached.newestAccepted().isPresent()) {
            return state.withSolved(reached.newestAccepted().get());
        }
        return state;
    }

    public Policy decide(State state, Instant now) {
        return new Policy(true, unlockedUntil(state, now), config.closeWarningMinutes(), config.dryRun(),
                config.blockedApps());
    }

    public boolean solvedToday(State state, Instant now) {
        // Not clock.isToday: a submission a few seconds "in the future" (clock skew) still counts.
        return state.lastSolvedAt() != null && !state.lastSolvedAt().isBefore(clock.dayStart(now));
    }

    public EmergencyUnlockResult requestEmergencyUnlock(State state, String phrase, Instant now) {
        Config.EmergencyUnlock rules = config.emergencyUnlock();
        if (!rules.confirmationPhrase().equals(phrase)) return new EmergencyUnlockResult.WrongPhrase();

        if (emergencyActive(state, now)) return new EmergencyUnlockResult.AlreadyActive(state.emergencyUnlockUntil());
        if (solvedToday(state, now)) return new EmergencyUnlockResult.NotNeeded();

        List<Instant> recent = state.emergencyUnlocks().stream()
                .filter(usedAt -> usedAt.isAfter(now.minus(ALLOWANCE_WINDOW)))
                .sorted()
                .toList();
        if (recent.size() >= rules.perWeek()) {
            // Free again once the oldest of the counted uses leaves the window.
            Optional<Instant> availableAt = rules.perWeek() == 0
                    ? Optional.empty()
                    : Optional.of(recent.get(recent.size() - rules.perWeek()).plus(ALLOWANCE_WINDOW));
            return new EmergencyUnlockResult.NoneLeft(availableAt);
        }

        Instant until = now.plus(Duration.ofMinutes(rules.durationMinutes()));
        return new EmergencyUnlockResult.Granted(state.withEmergencyUnlock(now, until), until);
    }

    private Instant unlockedUntil(State state, Instant now) {
        Instant until = solvedToday(state, now) ? clock.nextDayStart(now) : null;
        if (emergencyActive(state, now) && (until == null || state.emergencyUnlockUntil().isAfter(until))) {
            until = state.emergencyUnlockUntil();
        }
        return until;
    }

    private static boolean emergencyActive(State state, Instant now) {
        return state.emergencyUnlockUntil() != null && state.emergencyUnlockUntil().isAfter(now);
    }
}
