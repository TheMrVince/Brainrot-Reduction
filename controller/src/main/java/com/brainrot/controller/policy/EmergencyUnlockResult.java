package com.brainrot.controller.policy;

import com.brainrot.controller.state.State;

import java.time.Instant;
import java.util.Optional;

/** The answer to an emergency unlock request. Only {@link Granted} carries a new state to save. */
public sealed interface EmergencyUnlockResult {
    record Granted(State state, Instant until) implements EmergencyUnlockResult {}

    record WrongPhrase() implements EmergencyUnlockResult {}

    /** The allowance is used up; {@code availableAt} is empty when emergency unlocks are disabled. */
    record NoneLeft(Optional<Instant> availableAt) implements EmergencyUnlockResult {}

    record AlreadyActive(Instant until) implements EmergencyUnlockResult {}

    /** Today's problem is already solved, so nothing is locked. */
    record NotNeeded() implements EmergencyUnlockResult {}
}
