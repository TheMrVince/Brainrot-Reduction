package com.brainrot.controller.service;

import com.brainrot.controller.leetcode.LeetCodeClient;
import com.brainrot.controller.policy.CheckResult;

import java.io.IOException;

/** Asks LeetCode what a user has solved. Never throws: trouble is an {@link CheckResult.Unreachable}. */
@FunctionalInterface
public interface LeetCodeChecker {
    int RECENT_SUBMISSIONS = 20;

    CheckResult check(String username);

    static LeetCodeChecker using(LeetCodeClient client) {
        return username -> {
            try {
                return CheckResult.Reached.from(client.fetchRecentAccepted(username, RECENT_SUBMISSIONS));
            } catch (IOException e) {
                return new CheckResult.Unreachable(e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new CheckResult.Unreachable("interrupted");
            }
        };
    }
}
