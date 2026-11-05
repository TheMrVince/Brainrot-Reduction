package com.brainrot.controller.policy;

import com.brainrot.controller.config.Config;
import com.brainrot.controller.leetcode.AcceptedSubmission;
import com.brainrot.controller.state.State;
import com.brainrot.controller.time.DayClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final String PHRASE = "I choose to waste my time";
    private static final AppRule DISCORD = new AppRule("Discord", "com.hnc.Discord", null, null, null);

    private final PolicyEngine engine = engineWith(1);

    private static Instant at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    private static PolicyEngine engineWith(int perWeek) {
        Config config = new Config("me", 4, List.of(DISCORD), 10, 300,
                new Config.EmergencyUnlock(perWeek, 60, PHRASE), false);
        return new PolicyEngine(config, new DayClock(ZONE, 4));
    }

    // --- the basic decision -------------------------------------------------------------

    @Test
    void lockedWhenNothingHasBeenSolved() {
        Policy policy = engine.decide(State.empty(), at(11, 10, 10, 0));

        assertTrue(policy.locked());
        assertNull(policy.unlockedUntil());
        assertFalse(policy.isUnlockedAt(at(11, 10, 10, 0)));
    }

    @Test
    void thePolicyCarriesTheConfiguredSettings() {
        Policy policy = engine.decide(State.empty(), at(11, 10, 10, 0));

        assertEquals(10, policy.closeWarningMinutes());
        assertFalse(policy.dryRun());
        assertEquals(List.of(DISCORD), policy.apps());
    }

    @Test
    void solvingTodayUnlocksUntilTheNextDayStart() {
        Instant now = at(11, 10, 15, 0);
        State state = State.empty().withSolved(at(11, 10, 12, 0));

        Policy policy = engine.decide(state, now);

        assertTrue(policy.locked(), "stays locked=true; the unlock is the end time");
        assertEquals(at(11, 11, 4, 0), policy.unlockedUntil());
        assertTrue(policy.isUnlockedAt(now));
    }

    @Test
    void aSolveBeforeTheDayStartBelongsToYesterday() {
        State state = State.empty().withSolved(at(11, 10, 3, 59));

        assertNull(engine.decide(state, at(11, 10, 10, 0)).unlockedUntil());
    }

    @Test
    void aSolveAtExactlyTheDayStartCounts() {
        State state = State.empty().withSolved(at(11, 10, 4, 0));

        assertEquals(at(11, 11, 4, 0), engine.decide(state, at(11, 10, 10, 0)).unlockedUntil());
    }

    @Test
    void anUnlockEndsAtTheDayStartWithoutAnyoneDoingAnything() {
        State state = State.empty().withSolved(at(11, 10, 12, 0));

        Policy lastMinute = engine.decide(state, at(11, 11, 3, 59));
        assertEquals(at(11, 11, 4, 0), lastMinute.unlockedUntil());

        Policy newDay = engine.decide(state, at(11, 11, 4, 0));
        assertNull(newDay.unlockedUntil());
    }

    @Test
    void aSubmissionSlightlyAheadOfOurClockStillCounts() {
        Instant now = at(11, 10, 10, 0);
        State state = State.empty().withSolved(now.plusSeconds(30));

        assertEquals(at(11, 11, 4, 0), engine.decide(state, now).unlockedUntil());
    }

    // --- what LeetCode said -------------------------------------------------------------

    @Test
    void observingASolveRemembersIt() {
        State state = engine.observe(State.empty(), new CheckResult.Reached(Optional.of(at(11, 10, 12, 0))));

        assertEquals(at(11, 10, 12, 0), state.lastSolvedAt());
    }

    @Test
    void observingNothingOrAnOlderSolveChangesNothing() {
        State state = State.empty().withSolved(at(11, 10, 12, 0));

        assertSame(state, engine.observe(state, new CheckResult.Reached(Optional.empty())));
        assertEquals(state, engine.observe(state, new CheckResult.Reached(Optional.of(at(11, 9, 12, 0)))));
    }

    @Test
    void aSubmissionListReducesToItsNewestEntry() {
        List<AcceptedSubmission> submissions = List.of(
                new AcceptedSubmission("B", "b", at(11, 10, 12, 0)),
                new AcceptedSubmission("A", "a", at(11, 10, 9, 0)));

        assertEquals(Optional.of(at(11, 10, 12, 0)), CheckResult.Reached.from(submissions).newestAccepted());
        assertEquals(Optional.empty(), CheckResult.Reached.from(List.of()).newestAccepted());
    }

    @Test
    void whenLeetCodeIsUnreachableTheEngineGoesByWhatItRemembers() {
        CheckResult offline = new CheckResult.Unreachable("no network");
        Instant now = at(11, 10, 15, 0);

        State solvedEarlier = State.empty().withSolved(at(11, 10, 12, 0));
        assertSame(solvedEarlier, engine.observe(solvedEarlier, offline));
        assertEquals(at(11, 11, 4, 0), engine.decide(engine.observe(solvedEarlier, offline), now).unlockedUntil());

        State neverSolved = State.empty();
        assertNull(engine.decide(engine.observe(neverSolved, offline), now).unlockedUntil());  // fail closed

        State solvedYesterday = State.empty().withSolved(at(11, 9, 20, 0));
        assertNull(engine.decide(engine.observe(solvedYesterday, offline), now).unlockedUntil());
    }

    // --- emergency unlock ---------------------------------------------------------------

    @Test
    void anEmergencyUnlockNeedsTheExactPhrase() {
        Instant now = at(11, 10, 10, 0);

        assertInstanceOf(EmergencyUnlockResult.WrongPhrase.class, engine.requestEmergencyUnlock(State.empty(), "please", now));
        assertInstanceOf(EmergencyUnlockResult.WrongPhrase.class, engine.requestEmergencyUnlock(State.empty(), PHRASE.toLowerCase(), now));
        assertInstanceOf(EmergencyUnlockResult.WrongPhrase.class, engine.requestEmergencyUnlock(State.empty(), null, now));
    }

    @Test
    void aGrantedUnlockLastsTheConfiguredTimeAndIsInThePolicy() {
        Instant now = at(11, 10, 10, 0);

        var granted = assertInstanceOf(EmergencyUnlockResult.Granted.class,
                engine.requestEmergencyUnlock(State.empty(), PHRASE, now));

        assertEquals(now.plus(Duration.ofMinutes(60)), granted.until());
        assertEquals(List.of(now), granted.state().emergencyUnlocks());
        assertEquals(granted.until(), engine.decide(granted.state(), now).unlockedUntil());
        assertNull(engine.decide(granted.state(), granted.until()).unlockedUntil(), "it ends by itself");
    }

    @Test
    void youCannotStackUnlocks() {
        Instant now = at(11, 10, 10, 0);
        State active = grant(State.empty(), now);

        var again = assertInstanceOf(EmergencyUnlockResult.AlreadyActive.class,
                engine.requestEmergencyUnlock(active, PHRASE, now.plusSeconds(600)));
        assertEquals(now.plus(Duration.ofMinutes(60)), again.until());
    }

    @Test
    void anUnlockIsNotNeededOnceTodayIsSolved() {
        State solved = State.empty().withSolved(at(11, 10, 9, 0));

        assertInstanceOf(EmergencyUnlockResult.NotNeeded.class,
                engine.requestEmergencyUnlock(solved, PHRASE, at(11, 10, 10, 0)));
    }

    @Test
    void theAllowanceIsAnyRolling7DaysNotACalendarWeek() {
        Instant first = at(11, 8, 23, 0);  // a Sunday night
        State used = grant(State.empty(), first);

        Instant mondayMorning = at(11, 9, 8, 0);
        var denied = assertInstanceOf(EmergencyUnlockResult.NoneLeft.class,
                engine.requestEmergencyUnlock(used, PHRASE, mondayMorning));
        assertEquals(Optional.of(first.plus(Duration.ofDays(7))), denied.availableAt());

        Instant almost = first.plus(Duration.ofDays(7)).minusSeconds(1);
        assertInstanceOf(EmergencyUnlockResult.NoneLeft.class, engine.requestEmergencyUnlock(used, PHRASE, almost));

        Instant exactly = first.plus(Duration.ofDays(7));
        assertInstanceOf(EmergencyUnlockResult.Granted.class, engine.requestEmergencyUnlock(used, PHRASE, exactly));
    }

    @Test
    void severalUnlocksPerWeekAreCountedIndividually() {
        PolicyEngine twice = engineWith(2);
        Instant t0 = at(11, 2, 10, 0);
        State state = grant(twice, State.empty(), t0);
        state = grant(twice, state, t0.plus(Duration.ofDays(1)));

        Instant now = t0.plus(Duration.ofDays(2));
        var denied = assertInstanceOf(EmergencyUnlockResult.NoneLeft.class,
                twice.requestEmergencyUnlock(state, PHRASE, now));
        assertEquals(Optional.of(t0.plus(Duration.ofDays(7))), denied.availableAt());  // the oldest use frees up first
    }

    @Test
    void emergencyUnlockCanBeSwitchedOff() {
        var denied = assertInstanceOf(EmergencyUnlockResult.NoneLeft.class,
                engineWith(0).requestEmergencyUnlock(State.empty(), PHRASE, at(11, 10, 10, 0)));

        assertEquals(Optional.empty(), denied.availableAt());
    }

    @Test
    void whenBothApplyTheLaterEndWins() {
        // Solved today (unlocked until 04:00), plus an unlock that runs past 04:00.
        State state = new State(at(11, 10, 12, 0), List.of(), at(11, 11, 4, 30));

        assertEquals(at(11, 11, 4, 30), engine.decide(state, at(11, 11, 3, 50)).unlockedUntil());

        State shortUnlock = new State(at(11, 10, 12, 0), List.of(), at(11, 10, 20, 0));
        assertEquals(at(11, 11, 4, 0), engine.decide(shortUnlock, at(11, 10, 19, 0)).unlockedUntil());
    }

    @Test
    void anExpiredUnlockHasNoEffect() {
        State state = new State(null, List.of(at(11, 9, 10, 0)), at(11, 9, 11, 0));

        assertNull(engine.decide(state, at(11, 10, 10, 0)).unlockedUntil());
    }

    private State grant(State state, Instant now) {
        return grant(engine, state, now);
    }

    private static State grant(PolicyEngine engine, State state, Instant now) {
        return assertInstanceOf(EmergencyUnlockResult.Granted.class,
                engine.requestEmergencyUnlock(state, PHRASE, now)).state();
    }
}
