package com.brainrot.controller;

import com.brainrot.controller.config.Config;
import com.brainrot.controller.config.ConfigException;
import com.brainrot.controller.config.ConfigStore;
import com.brainrot.controller.ipc.EnforcerClient;
import com.brainrot.controller.leetcode.LeetCodeClient;
import com.brainrot.controller.service.ControllerService;
import com.brainrot.controller.service.LeetCodeChecker;
import com.brainrot.controller.service.PolicySink;
import com.brainrot.controller.state.State;
import com.brainrot.controller.state.StateStore;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Runs the controller until it is stopped: polls LeetCode and keeps the enforcer's policy current. */
final class RunCommand {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int EXIT_BAD_SETUP = 2;

    private RunCommand() {}

    static int run(List<String> args) throws InterruptedException {
        CliOptions options = CliOptions.parse(args);
        if (options == null) return EXIT_BAD_SETUP;

        Config config;
        State state;
        StateStore store = new StateStore(options.state());
        try {
            config = ConfigStore.load(options.config());
            state = store.load();
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return EXIT_BAD_SETUP;
        } catch (IOException e) {
            System.err.println("Cannot use saved state: " + e.getMessage());
            return EXIT_BAD_SETUP;
        }

        LeetCodeClient leetCode = new LeetCodeClient();
        if (!usernameLooksRight(leetCode, config.leetcodeUsername())) return EXIT_BAD_SETUP;

        EnforcerClient enforcer = new EnforcerClient(options.socket(), Duration.ofSeconds(5),
                event -> log("enforcer event: " + event));
        ControllerService service = new ControllerService(config, state, store, LeetCodeChecker.using(leetCode),
                PolicySink.toEnforcer(enforcer), Clock.systemDefaultZone(), RunCommand::log,
                ControllerService.Timing.standard());

        log("started for " + config.leetcodeUsername() + "; checking LeetCode every "
                + config.pollIntervalSeconds() + "s while locked; enforcer socket " + options.socket());
        if (config.dryRun()) log("dryRun is on: the enforcer will only log what it would close");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("stopping");
            service.close();
            enforcer.close();
            stopped.countDown();
        }, "shutdown"));

        service.start();
        stopped.await();
        return 0;
    }

    /** A mistyped name looks exactly like "never solved anything", so refuse it up front. Offline is not an error. */
    private static boolean usernameLooksRight(LeetCodeClient client, String username) throws InterruptedException {
        try {
            if (client.userExists(username)) return true;
            System.err.println("LeetCode has no user named \"" + username + "\"; check leetcodeUsername in the config.");
            return false;
        } catch (IOException e) {
            log("could not confirm the LeetCode username (" + e.getMessage() + "); continuing");
            return true;
        }
    }

    private static void log(String message) {
        System.out.println(TIME.format(LocalTime.now()) + "  " + message);
    }
}
