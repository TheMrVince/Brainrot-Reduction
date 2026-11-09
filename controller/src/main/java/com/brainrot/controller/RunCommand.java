package com.brainrot.controller;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Runs the controller until it is stopped: polls LeetCode and keeps the enforcer's policy current. */
final class RunCommand {
    private static final int EXIT_BAD_SETUP = 2;

    private RunCommand() {}

    static int run(List<String> args) throws InterruptedException {
        CliOptions options = CliOptions.parse(args);
        if (options == null) return EXIT_BAD_SETUP;

        ControllerRuntime runtime = ControllerRuntime.create(options);
        if (runtime == null) return EXIT_BAD_SETUP;

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ControllerRuntime.log("stopping");
            runtime.close();
            stopped.countDown();
        }, "shutdown"));

        runtime.start();
        stopped.await();
        return 0;
    }
}
