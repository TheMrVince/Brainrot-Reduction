package com.brainrot.controller;

import com.brainrot.controller.ui.Theme;
import com.brainrot.controller.ui.TrayApp;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Runs the controller with a menu-bar icon: status at a glance, "Check now", and the emergency unlock. */
final class TrayCommand {
    private static final int EXIT_BAD_SETUP = 2;

    private TrayCommand() {}

    static int run(List<String> args) throws InterruptedException, InvocationTargetException {
        // Menu-bar only: no Dock icon. Must be set before anything touches AWT.
        System.setProperty("apple.awt.UIElement", "true");

        CliOptions options = CliOptions.parse(args);
        if (options == null) return EXIT_BAD_SETUP;

        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            System.err.println("This system has no tray; use `run` instead.");
            return EXIT_BAD_SETUP;
        }

        ControllerRuntime runtime = ControllerRuntime.create(options);
        if (runtime == null) return EXIT_BAD_SETUP;

        CountDownLatch stopped = new CountDownLatch(1);
        TrayApp[] app = new TrayApp[1];

        Runnable quit = () -> {
            ControllerRuntime.log("stopping");
            runtime.close();
            if (app[0] != null) app[0].remove();
            stopped.countDown();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (stopped.getCount() > 0) quit.run();  // Ctrl+C or a plain kill
        }, "shutdown"));

        boolean[] installed = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.apply();
            app[0] = new TrayApp(runtime.service, runtime.config, quit);
            installed[0] = app[0].install();
        });
        if (!installed[0]) {
            System.err.println("Could not put an icon in the menu bar.");
            runtime.close();
            return EXIT_BAD_SETUP;
        }

        ControllerRuntime.log("tray icon installed");
        runtime.start();
        stopped.await();
        return 0;
    }
}
