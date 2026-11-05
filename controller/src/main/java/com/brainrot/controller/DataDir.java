package com.brainrot.controller;

import java.nio.file.Path;

/** Where the controller and the enforcer keep their files (same folder on both sides). */
public final class DataDir {
    private DataDir() {}

    public static Path path() {
        return Path.of(System.getProperty("user.home"), ".brainrot-reduction");
    }

    public static Path config() {
        return path().resolve("config.json");
    }

    public static Path state() {
        return path().resolve("state.json");
    }

    public static Path enforcerSocket() {
        return path().resolve("enforcer.sock");
    }
}
