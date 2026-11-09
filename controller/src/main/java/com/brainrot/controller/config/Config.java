package com.brainrot.controller.config;

import com.brainrot.controller.policy.AppRule;

import java.util.List;

/** Settings the user chooses. Validated by {@link ConfigStore}. */
public record Config(
        String leetcodeUsername,
        int dayStartHour,
        List<AppRule> blockedApps,
        int closeWarningMinutes,
        int pollIntervalSeconds,
        EmergencyUnlock emergencyUnlock,
        boolean dryRun) {

    public record EmergencyUnlock(int perWeek, int durationMinutes, String confirmationPhrase) {}
}
