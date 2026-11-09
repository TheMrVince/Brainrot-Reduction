package com.brainrot.controller;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The options shared by the command-line commands. Anything that is not an option ends up in {@code rest}. */
record CliOptions(Path config, Path state, Path socket, boolean send, List<String> rest) {

    /** Null (after printing why) if an option is unknown or incomplete. */
    static CliOptions parse(List<String> args) {
        Path config = DataDir.config();
        Path state = DataDir.state();
        Path socket = DataDir.enforcerSocket();
        boolean send = false;
        List<String> rest = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            boolean hasValue = i + 1 < args.size();
            if (arg.equals("--config") && hasValue) {
                config = Path.of(args.get(++i));
            } else if (arg.equals("--state") && hasValue) {
                state = Path.of(args.get(++i));
            } else if (arg.equals("--socket") && hasValue) {
                socket = Path.of(args.get(++i));
            } else if (arg.equals("--send")) {
                send = true;
            } else if (arg.startsWith("--")) {
                System.err.println("Unknown or incomplete option: " + arg);
                return null;
            } else {
                rest.add(arg);
            }
        }
        return new CliOptions(config, state, socket, send, rest);
    }
}
