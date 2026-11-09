package com.brainrot.controller.ui;

import com.brainrot.controller.policy.EmergencyUnlockResult;
import com.brainrot.controller.service.StatusSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides what the tray says. Pure: a status goes in, words come out, so the wording and the
 * "should this notify?" rules are tested without any windows.
 */
public final class StatusPresenter {

    /**
     * @param locked       which icon to show
     * @param headline     the main line, also the tooltip
     * @param details      smaller lines under the headline
     * @param notification a pop-up to show now, or null
     */
    public record View(boolean locked, String headline, List<String> details, String notification) {}

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

    private final ZoneId zone;

    public StatusPresenter(ZoneId zone) {
        this.zone = zone;
    }

    /** {@code previous} is what was shown before, or null the first time; it decides whether to notify. */
    public View present(StatusSnapshot status, Instant now, StatusSnapshot previous) {
        return new View(!status.unlocked(), headline(status, now), details(status, now), notification(status, now, previous));
    }

    public String describe(EmergencyUnlockResult result, Instant now) {
        return switch (result) {
            case EmergencyUnlockResult.Granted granted -> "Emergency unlock until " + time(granted.until(), now) + ".";
            case EmergencyUnlockResult.WrongPhrase wrong -> "That is not the phrase. Type it exactly, capitals included.";
            case EmergencyUnlockResult.NotNeeded notNeeded -> "Not needed: today's problem is already solved.";
            case EmergencyUnlockResult.AlreadyActive active ->
                    "An emergency unlock is already running until " + time(active.until(), now) + ".";
            case EmergencyUnlockResult.NoneLeft none -> none.availableAt()
                    .map(at -> "No emergency unlocks left. The next one is available " + time(at, now) + ".")
                    .orElse("Emergency unlocks are switched off in the config.");
        };
    }

    /** "04:00" today, "tomorrow 04:00", otherwise "Sat 14 Nov 04:00". */
    String time(Instant moment, Instant now) {
        LocalDate day = moment.atZone(zone).toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        String clock = CLOCK.format(moment.atZone(zone));
        if (day.equals(today)) return clock;
        if (day.equals(today.plusDays(1))) return "tomorrow " + clock;
        return WEEKDAY.format(moment.atZone(zone));
    }

    private String headline(StatusSnapshot status, Instant now) {
        return switch (status.reason()) {
            case LOCKED -> "Locked: solve a LeetCode problem to unlock";
            case SOLVED_TODAY -> "Unlocked until " + time(status.unlockedUntil(), now) + " (solved today)";
            case EMERGENCY_UNLOCK -> "Emergency unlock until " + time(status.unlockedUntil(), now);
        };
    }

    private List<String> details(StatusSnapshot status, Instant now) {
        List<String> lines = new ArrayList<>();

        if (status.leetCodeReachable() == null) {
            lines.add("LeetCode: not checked yet");
        } else if (status.leetCodeReachable()) {
            lines.add("LeetCode: reachable" + (status.lastCheckAt() == null ? ""
                    : ", checked " + CLOCK.format(status.lastCheckAt().atZone(zone))));
        } else {
            lines.add("LeetCode: unreachable, using what was remembered");
        }

        if (status.enforcerReachable() == null) {
            lines.add("Enforcer: connecting...");
        } else if (status.enforcerReachable()) {
            lines.add("Enforcer: connected");
        } else {
            lines.add("Enforcer: not reachable (is `enforcer serve` running?)");
        }

        if (status.dryRun()) lines.add("Dry run: nothing is really closed");
        return lines;
    }

    private String notification(StatusSnapshot status, Instant now, StatusSnapshot previous) {
        if (previous == null) return null;

        if (!previous.unlocked() && status.unlocked()) {
            return (status.reason() == StatusSnapshot.Reason.SOLVED_TODAY ? "Solved. " : "Emergency unlock. ")
                    + "Unlocked until " + time(status.unlockedUntil(), now) + ".";
        }
        if (previous.unlocked() && !status.unlocked()) {
            return "Locked again. Solve a LeetCode problem to unlock.";
        }
        if (Boolean.TRUE.equals(previous.enforcerReachable()) && Boolean.FALSE.equals(status.enforcerReachable())) {
            return "The enforcer is not reachable, so blocking may not be active.";
        }
        if (Boolean.FALSE.equals(previous.enforcerReachable()) && Boolean.TRUE.equals(status.enforcerReachable())) {
            return "The enforcer is reachable again.";
        }
        return null;
    }
}
