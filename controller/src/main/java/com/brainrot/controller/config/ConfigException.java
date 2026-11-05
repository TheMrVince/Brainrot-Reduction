package com.brainrot.controller.config;

/** The config file is missing, unreadable or invalid; the message says what to fix. */
public final class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }
}
