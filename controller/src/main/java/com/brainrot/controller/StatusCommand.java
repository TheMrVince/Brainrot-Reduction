package com.brainrot.controller;

import com.brainrot.controller.config.Config;
import com.brainrot.controller.config.ConfigException;
import com.brainrot.controller.config.ConfigStore;
import com.brainrot.controller.leetcode.LeetCodeClient;
import com.brainrot.controller.policy.CheckResult;
import com.brainrot.controller.policy.EmergencyUnlockResult;
import com.brainrot.controller.policy.Policy;
import com.brainrot.controller.policy.PolicyEngine;
import com.brainrot.controller.policy.PolicyJson;
import com.brainrot.controller.state.State;
import com.brainrot.controller.state.StateStore;
import com.brainrot.controller.time.DayClock;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line view of the decision: {@code status} asks LeetCode, updates the saved state and shows
 * the policy that would go to the enforcer; {@code unlock} tries an emergency unlock. The tray UI
 * and scheduler will drive the same engine later.
 */
final class StatusCommand {
    private static final int FETCH_LIMIT = 20;
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private record Options(Path config, Path state, List<String> rest) {}

    private StatusCommand() {}

    static int status(List<String> args) {
        Setup setup = Setup.from(args);
        if (setup == null) return 2;
        Instant now = Instant.now();

        CheckResult result = check(setup.config);
        if (result instanceof CheckResult.Reached reached) {
            System.out.println("LeetCode: reached; newest accepted submission: "
                    + reached.newestAccepted().map(DISPLAY::format).orElse("none in the recent list"));
        } else if (result instanceof CheckResult.Unreachable offline) {
            System.out.println("LeetCode: unreachable (" + offline.reason() + "); going by what was remembered");
        }

        State state = setup.engine.observe(setup.state, result);
        if (!saveState(setup, state)) return 1;

        System.out.println("Day started at " + DISPLAY.format(setup.clock.dayStart(now)));
        printDecision(setup, state, now);
        return 0;
    }

    static int unlock(List<String> args) {
        Setup setup = Setup.from(args);
        if (setup == null) return 2;
        Instant now = Instant.now();
        String phrase = setup.rest.isEmpty() ? null : String.join(" ", setup.rest);

        EmergencyUnlockResult result = setup.engine.requestEmergencyUnlock(setup.state, phrase, now);
        switch (result) {
            case EmergencyUnlockResult.Granted granted -> {
                if (!saveState(setup, granted.state())) return 1;
                System.out.println("Emergency unlock granted until " + DISPLAY.format(granted.until()));
                printDecision(setup, granted.state(), now);
                return 0;
            }
            case EmergencyUnlockResult.WrongPhrase w -> System.out.println("Not unlocked: the phrase did not match.");
            case EmergencyUnlockResult.NotNeeded n -> System.out.println("Not needed: today's problem is already solved.");
            case EmergencyUnlockResult.AlreadyActive active ->
                    System.out.println("Already unlocked until " + DISPLAY.format(active.until()) + ".");
            case EmergencyUnlockResult.NoneLeft none -> System.out.println(none.availableAt()
                    .map(at -> "No emergency unlocks left; the next one is available " + DISPLAY.format(at) + ".")
                    .orElse("Emergency unlocks are switched off in the config."));
        }
        return 1;
    }

    private static void printDecision(Setup setup, State state, Instant now) {
        Policy policy = setup.engine.decide(state, now);
        if (policy.isUnlockedAt(now)) {
            String why = setup.engine.solvedToday(state, now) ? "solved today" : "emergency unlock";
            System.out.println("Status: UNLOCKED until " + DISPLAY.format(policy.unlockedUntil()) + " (" + why + ")");
        } else {
            System.out.println("Status: LOCKED. Solve a problem on LeetCode to unlock.");
        }
        System.out.println("Policy message for the enforcer:");
        System.out.println(PolicyJson.toSetPolicyMessage(policy));
    }

    private static CheckResult check(Config config) {
        try {
            return CheckResult.Reached.from(new LeetCodeClient().fetchRecentAccepted(config.leetcodeUsername(), FETCH_LIMIT));
        } catch (IOException e) {
            return new CheckResult.Unreachable(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult.Unreachable("interrupted");
        }
    }

    private static boolean saveState(Setup setup, State state) {
        try {
            setup.store.save(state);
            return true;
        } catch (IOException e) {
            System.err.println("Could not save state: " + e.getMessage());
            return false;
        }
    }

    /** Everything a command needs, loaded from the config and state files. */
    private static final class Setup {
        final Config config;
        final StateStore store;
        final State state;
        final DayClock clock;
        final PolicyEngine engine;
        final List<String> rest;

        private Setup(Config config, StateStore store, State state, List<String> rest) {
            this.config = config;
            this.store = store;
            this.state = state;
            this.rest = rest;
            this.clock = new DayClock(ZoneId.systemDefault(), config.dayStartHour());
            this.engine = new PolicyEngine(config, clock);
        }

        /** Null (after printing why) if the config or state cannot be used. */
        static Setup from(List<String> args) {
            Options options = parse(args);
            if (options == null) return null;
            try {
                Config config = ConfigStore.load(options.config);
                StateStore store = new StateStore(options.state);
                return new Setup(config, store, store.load(), options.rest);
            } catch (ConfigException e) {
                System.err.println(e.getMessage());
            } catch (IOException e) {
                System.err.println("Cannot use saved state: " + e.getMessage());
            }
            return null;
        }
    }

    private static Options parse(List<String> args) {
        Path dir = Path.of(System.getProperty("user.home"), ".brainrot-reduction");
        Path config = dir.resolve("config.json");
        Path state = dir.resolve("state.json");
        List<String> rest = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--config") && i + 1 < args.size()) {
                config = Path.of(args.get(++i));
            } else if (arg.equals("--state") && i + 1 < args.size()) {
                state = Path.of(args.get(++i));
            } else if (arg.startsWith("--")) {
                System.err.println("Unknown or incomplete option: " + arg);
                return null;
            } else {
                rest.add(arg);
            }
        }
        return new Options(config, state, rest);
    }
}
