package com.brainrot.controller.ui;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Gives the dialogs a modern look that matches the system's light or dark appearance. */
public final class Theme {
    private Theme() {}

    public static void apply() {
        if (isMacDarkMode()) FlatMacDarkLaf.setup();
        else FlatMacLightLaf.setup();
    }

    private static boolean isMacDarkMode() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return false;
        try {
            Process process = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return new String(process.getInputStream().readAllBytes()).trim().equalsIgnoreCase("Dark");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
