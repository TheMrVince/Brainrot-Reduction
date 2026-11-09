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
import java.util.function.Consumer;

/** The real wiring behind {@code run} and {@code tray}: config, state, LeetCode, the enforcer and the service. */
final class ControllerRuntime implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    final Config config;
    final ControllerService service;
    private final EnforcerClient enforcer;

    private ControllerRuntime(Config config, ControllerService service, EnforcerClient enforcer) {
        this.config = config;
        this.service = service;
        this.enforcer = enforcer;
    }

    /** Prints a line with the time of day, like the controller's own log. */
    static void log(String message) {
        System.out.println(TIME.format(LocalTime.now()) + "  " + message);
    }

    /** Null (after printing why) if the config, saved state or LeetCode username cannot be used. */
    static ControllerRuntime create(CliOptions options) {
        Config config;
        State state;
        StateStore store = new StateStore(options.state());
        try {
            config = ConfigStore.load(options.config());
            state = store.load();
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return null;
        } catch (IOException e) {
            System.err.println("Cannot use saved state: " + e.getMessage());
            return null;
        }

        LeetCodeClient leetCode = new LeetCodeClient();
        if (!usernameLooksRight(leetCode, config.leetcodeUsername())) return null;

        EnforcerClient enforcer = new EnforcerClient(options.socket(), Duration.ofSeconds(5),
                event -> log("enforcer event: " + event));
        ControllerService service = new ControllerService(config, state, store, LeetCodeChecker.using(leetCode),
                PolicySink.toEnforcer(enforcer), Clock.systemDefaultZone(), ControllerRuntime::log,
                ControllerService.Timing.standard());

        log("started for " + config.leetcodeUsername() + "; checking LeetCode every "
                + config.pollIntervalSeconds() + "s while locked; enforcer socket " + options.socket());
        if (config.dryRun()) log("dryRun is on: the enforcer will only log what it would close");
        return new ControllerRuntime(config, service, enforcer);
    }

    void start() {
        service.start();
    }

    @Override
    public void close() {
        service.close();
        enforcer.close();
    }

    /** A mistyped name looks exactly like "never solved anything", so refuse it up front. Offline is not an error. */
    private static boolean usernameLooksRight(LeetCodeClient client, String username) {
        try {
            if (client.userExists(username)) return true;
            System.err.println("LeetCode has no user named \"" + username + "\"; check leetcodeUsername in the config.");
            return false;
        } catch (IOException e) {
            log("could not confirm the LeetCode username (" + e.getMessage() + "); continuing");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
