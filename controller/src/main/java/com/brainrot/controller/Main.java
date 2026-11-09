package com.brainrot.controller;

import com.brainrot.controller.leetcode.AcceptedSubmission;
import com.brainrot.controller.leetcode.LeetCodeClient;
import com.brainrot.controller.time.DayClock;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class Main {
    private static final int DAY_START_HOUR = 4;
    private static final int FETCH_LIMIT = 20;
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private Main() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length >= 1 && args[0].equals("tray")) {
            try {
                System.exit(TrayCommand.run(List.of(args).subList(1, args.length)));
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
        if (args.length >= 1 && args[0].equals("run")) {
            System.exit(RunCommand.run(List.of(args).subList(1, args.length)));
        }
        if (args.length >= 1 && args[0].equals("status")) {
            System.exit(StatusCommand.status(List.of(args).subList(1, args.length)));
        }
        if (args.length >= 1 && args[0].equals("unlock")) {
            System.exit(StatusCommand.unlock(List.of(args).subList(1, args.length)));
        }
        if (args.length != 1) {
            System.err.println("Usage:\n"
                    + "  tray   [--config FILE] [--state FILE] [--socket FILE]   (menu-bar icon, keeps running)\n"
                    + "  run    [--config FILE] [--state FILE] [--socket FILE]   (no icon, keeps running)\n"
                    + "  status [--send] [--config FILE] [--state FILE] [--socket FILE]\n"
                    + "  unlock [--send] [--config FILE] [--state FILE] [--socket FILE] <phrase>\n"
                    + "  <leetcode-username>   (just check one user, no config needed)");
            System.exit(2);
        }

        DayClock clock = new DayClock(ZoneId.systemDefault(), DAY_START_HOUR);
        Instant now = Instant.now();

        List<AcceptedSubmission> recent;
        try {
            recent = new LeetCodeClient().fetchRecentAccepted(args[0], FETCH_LIMIT);
        } catch (IOException e) {
            System.err.println("Could not check LeetCode: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Day started at " + DISPLAY.format(clock.dayStart(now)));
        for (AcceptedSubmission s : recent.subList(0, Math.min(5, recent.size()))) {
            String marker = clock.isToday(s.solvedAt(), now) ? "*" : " ";
            System.out.println(marker + " " + DISPLAY.format(s.solvedAt()) + "  " + s.title());
        }

        boolean solvedToday = recent.stream().anyMatch(s -> clock.isToday(s.solvedAt(), now));
        System.out.println("Solved today: " + (solvedToday ? "YES" : "NO"));
    }
}
