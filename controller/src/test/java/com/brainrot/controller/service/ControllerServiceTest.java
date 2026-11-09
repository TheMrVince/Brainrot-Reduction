package com.brainrot.controller.service;

import com.brainrot.controller.config.Config;
import com.brainrot.controller.policy.AppRule;
import com.brainrot.controller.policy.CheckResult;
import com.brainrot.controller.policy.EmergencyUnlockResult;
import com.brainrot.controller.policy.Policy;
import com.brainrot.controller.state.State;
import com.brainrot.controller.state.StateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerServiceTest {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final String PHRASE = "I choose to waste my time";
    private static final AppRule DISCORD = new AppRule("Discord", "com.hnc.Discord", null, null, null);
    private static final Config CONFIG = new Config("me", 4, List.of(DISCORD), 10, 300,
            new Config.EmergencyUnlock(1, 60, PHRASE), false);

    private static Instant at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        void set(Instant to) {
            now = to;
        }

        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeLeetCode implements LeetCodeChecker {
        volatile CheckResult answer = new CheckResult.Reached(Optional.empty());
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CheckResult check(String username) {
            calls.incrementAndGet();
            return answer;
        }
    }

    private static final class FakeEnforcer implements PolicySink {
        final List<Policy> delivered = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        volatile boolean down;

        @Override
        public void deliver(Policy policy) throws IOException {
            attempts.incrementAndGet();
            if (down) throw new IOException("no enforcer");
            delivered.add(policy);
        }

        Policy last() {
            return delivered.get(delivered.size() - 1);
        }
    }

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(at(11, 10, 10, 0));
    private final FakeLeetCode leetCode = new FakeLeetCode();
    private final FakeEnforcer enforcer = new FakeEnforcer();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private StateStore store;

    @BeforeEach
    void setUp() {
        store = new StateStore(dir.resolve("state.json"));
    }

    private ControllerService service() {
        return service(State.empty());
    }

    private ControllerService service(State initial) {
        return new ControllerService(CONFIG, initial, store, leetCode, enforcer, clock, logs::add,
                ControllerService.Timing.standard());
    }

    private void tickAfter(ControllerService service, Duration wait) {
        clock.advance(wait);
        service.tick();
    }

    private long logged(String part) {
        return logs.stream().filter(line -> line.contains(part)).count();
    }

    // --- polling ------------------------------------------------------------------------

    @Test
    void theFirstTickChecksLeetCodeAndDeliversALockedPolicy() {
        service().tick();

        assertEquals(1, leetCode.calls.get());
        assertEquals(1, enforcer.delivered.size());
        assertTrue(enforcer.last().locked());
        assertNull(enforcer.last().unlockedUntil());
    }

    @Test
    void whileLockedItAsksLeetCodeOnlyOncePerPollInterval() {
        ControllerService service = service();
        service.tick();  // 10:00:00, the first check

        for (Instant moment : List.of(at(11, 10, 10, 0).plusSeconds(10), at(11, 10, 10, 1), at(11, 10, 10, 4).plusSeconds(59))) {
            clock.set(moment);
            service.tick();
        }
        assertEquals(1, leetCode.calls.get(), "still inside the 300 s interval");

        clock.set(at(11, 10, 10, 5));  // exactly 300 s after the first check
        service.tick();
        assertEquals(2, leetCode.calls.get());
    }

    @Test
    void aSolveIsPickedUpAndUnlocksOnTheSameTick() {
        leetCode.answer = new CheckResult.Reached(Optional.of(at(11, 10, 9, 0)));
        service().tick();

        assertEquals(at(11, 11, 4, 0), enforcer.last().unlockedUntil());
    }

    @Test
    void theSolveIsSavedSoARestartRemembersIt() throws IOException {
        leetCode.answer = new CheckResult.Reached(Optional.of(at(11, 10, 9, 0)));
        service().tick();

        assertEquals(at(11, 10, 9, 0), store.load().lastSolvedAt());
    }

    @Test
    void onceSolvedItStopsAskingUntilTheDayEnds() {
        leetCode.answer = new CheckResult.Reached(Optional.of(at(11, 10, 9, 0)));
        ControllerService service = service();
        service.tick();

        clock.set(at(11, 10, 20, 0));
        service.tick();
        clock.set(at(11, 11, 3, 59));
        service.tick();
        assertEquals(1, leetCode.calls.get(), "no network while unlocked for the day");

        clock.set(at(11, 11, 4, 0));  // a new day: yesterday's solve no longer counts
        service.tick();
        assertEquals(2, leetCode.calls.get());
        assertNull(enforcer.last().unlockedUntil());
        assertTrue(enforcer.last().locked());
    }

    @Test
    void aRememberedSolveMeansNoNetworkCallAtStartup() {
        service(State.empty().withSolved(at(11, 10, 8, 0))).tick();

        assertEquals(0, leetCode.calls.get());
        assertEquals(at(11, 11, 4, 0), enforcer.last().unlockedUntil());
    }

    @Test
    void anUnreachableLeetCodeKeepsThingsLockedAndIsLoggedOncePerOutage() {
        leetCode.answer = new CheckResult.Unreachable("no network");
        ControllerService service = service();
        service.tick();
        tickAfter(service, Duration.ofSeconds(300));
        tickAfter(service, Duration.ofSeconds(300));

        assertEquals(3, leetCode.calls.get());
        assertTrue(enforcer.last().locked());
        assertNull(enforcer.last().unlockedUntil(), "fail closed");
        assertEquals(1, logged("unreachable"));

        leetCode.answer = new CheckResult.Reached(Optional.of(clock.instant().minusSeconds(60)));
        tickAfter(service, Duration.ofSeconds(300));
        assertEquals(1, logged("reachable again"));
        assertTrue(enforcer.last().unlockedUntil() != null);
    }

    @Test
    void requestCheckSkipsTheWait() {
        ControllerService service = service();
        service.tick();
        tickAfter(service, Duration.ofSeconds(20));
        assertEquals(1, leetCode.calls.get());

        service.requestCheck();
        tickAfter(service, Duration.ofSeconds(10));

        assertEquals(2, leetCode.calls.get());
    }

    // --- delivering ---------------------------------------------------------------------

    @Test
    void thePolicyGoesOutOnChangeAndAsAHeartbeatOtherwise() {
        ControllerService service = service();
        service.tick();
        for (int i = 0; i < 5; i++) tickAfter(service, Duration.ofSeconds(10));
        assertEquals(1, enforcer.delivered.size(), "nothing new to say yet");

        tickAfter(service, Duration.ofSeconds(10));  // 60 s since the last delivery
        assertEquals(2, enforcer.delivered.size());
        assertEquals(enforcer.delivered.get(0), enforcer.delivered.get(1));
    }

    @Test
    void aChangedPolicyIsDeliveredAtOnceNotAtTheNextHeartbeat() {
        ControllerService service = service();
        service.tick();
        leetCode.answer = new CheckResult.Reached(Optional.of(at(11, 10, 10, 3)));

        clock.set(at(11, 10, 10, 5));
        service.tick();

        assertEquals(2, enforcer.delivered.size());
        assertTrue(enforcer.last().unlockedUntil() != null);
    }

    @Test
    void aDownEnforcerIsRetriedEveryTickAndReportedOnce() {
        enforcer.down = true;
        ControllerService service = service();
        service.tick();
        tickAfter(service, Duration.ofSeconds(10));
        tickAfter(service, Duration.ofSeconds(10));

        assertEquals(3, enforcer.attempts.get());
        assertEquals(1, logged("could not deliver"));

        enforcer.down = false;
        tickAfter(service, Duration.ofSeconds(10));

        assertEquals(1, enforcer.delivered.size());
        assertEquals(1, logged("reachable again"));
        assertTrue(logs.get(logs.size() - 1).contains("policy delivered"));
    }

    @Test
    void theHeartbeatBringsARestartedEnforcerUpToDate() {
        ControllerService service = service();
        service.tick();
        enforcer.delivered.clear();  // the enforcer "restarted" and forgot everything

        for (int i = 0; i < 6; i++) tickAfter(service, Duration.ofSeconds(10));

        assertEquals(1, enforcer.delivered.size());
    }

    // --- emergency unlock ---------------------------------------------------------------

    @Test
    void anEmergencyUnlockIsDeliveredImmediatelyAndSaved() throws IOException {
        ControllerService service = service();
        service.tick();
        int before = enforcer.delivered.size();

        EmergencyUnlockResult result = service.requestEmergencyUnlock(PHRASE);

        var granted = assertInstanceOf(EmergencyUnlockResult.Granted.class, result);
        assertEquals(before + 1, enforcer.delivered.size());
        assertEquals(granted.until(), enforcer.last().unlockedUntil());
        assertEquals(clock.instant().plus(Duration.ofMinutes(60)), granted.until());
        assertEquals(granted.until(), store.load().emergencyUnlockUntil());
    }

    @Test
    void aRefusedEmergencyUnlockChangesNothing() throws IOException {
        ControllerService service = service();
        service.tick();
        int before = enforcer.delivered.size();

        assertInstanceOf(EmergencyUnlockResult.WrongPhrase.class, service.requestEmergencyUnlock("please"));

        assertEquals(before, enforcer.delivered.size());
        assertNull(store.load().emergencyUnlockUntil());
    }

    @Test
    void theUnlockEndsOnItsOwnAndTheLockIsDeliveredAgain() {
        ControllerService service = service();
        service.tick();
        service.requestEmergencyUnlock(PHRASE);

        tickAfter(service, Duration.ofMinutes(61));

        assertNull(enforcer.last().unlockedUntil());
    }

    // --- staying alive ------------------------------------------------------------------

    @Test
    void aCrashingCollaboratorDoesNotStopTheLoop() {
        LeetCodeChecker exploding = username -> {
            throw new IllegalStateException("boom");
        };
        ControllerService service = new ControllerService(CONFIG, State.empty(), store, exploding, enforcer, clock,
                logs::add, ControllerService.Timing.standard());

        service.safeTick();
        service.safeTick();

        assertEquals(2, logged("unexpected error"));
    }

    @Test
    void theBackgroundThreadTicksAndStopsWhenClosed() throws Exception {
        ControllerService service = new ControllerService(CONFIG, State.empty(), store, leetCode, enforcer, clock,
                logs::add, new ControllerService.Timing(Duration.ofMillis(20), Duration.ofHours(1)));

        service.start();
        service.start();  // a second start does nothing
        for (int i = 0; i < 100 && enforcer.delivered.isEmpty(); i++) Thread.sleep(20);
        service.close();
        service.close();  // harmless twice

        assertTrue(!enforcer.delivered.isEmpty());
        int attemptsAtStop = enforcer.attempts.get();
        Thread.sleep(150);
        assertEquals(attemptsAtStop, enforcer.attempts.get(), "no ticks after close");
    }

    // --- the status feed for the UI -------------------------------------------------------

    private List<StatusSnapshot> listen(ControllerService service) {
        List<StatusSnapshot> seen = new CopyOnWriteArrayList<>();
        service.addStatusListener(seen::add);
        return seen;
    }

    @Test
    void theFirstStatusDescribesALockedStateWithBothConnectionsKnown() {
        ControllerService service = service();
        List<StatusSnapshot> seen = listen(service);

        service.tick();

        assertEquals(1, seen.size());
        StatusSnapshot status = seen.get(0);
        assertEquals(StatusSnapshot.Reason.LOCKED, status.reason());
        assertNull(status.unlockedUntil());
        assertEquals(Boolean.TRUE, status.leetCodeReachable());
        assertEquals(Boolean.TRUE, status.enforcerReachable());
        assertEquals(clock.instant(), status.lastCheckAt());
        assertEquals(false, status.dryRun());
    }

    @Test
    void listenersHearOnlyAboutChangesNotEveryTick() {
        ControllerService service = service();
        List<StatusSnapshot> seen = listen(service);

        service.tick();
        for (int i = 0; i < 5; i++) tickAfter(service, Duration.ofSeconds(10));

        assertEquals(1, seen.size());
    }

    @Test
    void solvingPublishesAnUnlockWithItsEndTime() {
        ControllerService service = service();
        List<StatusSnapshot> seen = listen(service);
        service.tick();

        leetCode.answer = new CheckResult.Reached(Optional.of(at(11, 10, 10, 2)));
        clock.set(at(11, 10, 10, 5));
        service.tick();

        StatusSnapshot status = seen.get(seen.size() - 1);
        assertEquals(StatusSnapshot.Reason.SOLVED_TODAY, status.reason());
        assertEquals(at(11, 11, 4, 0), status.unlockedUntil());
        assertTrue(status.unlocked());
    }

    @Test
    void anEmergencyUnlockIsPublishedAsSuch() {
        ControllerService service = service();
        List<StatusSnapshot> seen = listen(service);
        service.tick();

        service.requestEmergencyUnlock(PHRASE);

        StatusSnapshot status = seen.get(seen.size() - 1);
        assertEquals(StatusSnapshot.Reason.EMERGENCY_UNLOCK, status.reason());
        assertEquals(clock.instant().plus(Duration.ofMinutes(60)), status.unlockedUntil());
    }

    @Test
    void theEnforcerBeingDownAndBackIsPublished() {
        ControllerService service = service();
        List<StatusSnapshot> seen = listen(service);
        service.tick();

        enforcer.down = true;
        tickAfter(service, Duration.ofSeconds(60));  // the heartbeat finds it gone
        assertEquals(Boolean.FALSE, seen.get(seen.size() - 1).enforcerReachable());

        enforcer.down = false;
        tickAfter(service, Duration.ofSeconds(10));
        assertEquals(Boolean.TRUE, seen.get(seen.size() - 1).enforcerReachable());
    }

    @Test
    void anUnreachableLeetCodeIsPublished() {
        leetCode.answer = new CheckResult.Unreachable("no network");
        ControllerService service = service();
        List<StatusSnapshot> seen = listen(service);

        service.tick();

        assertEquals(Boolean.FALSE, seen.get(0).leetCodeReachable());
        assertNull(seen.get(0).lastCheckAt(), "it has never been answered");
    }

    @Test
    void aListenerThatJoinsLateGetsTheCurrentStatusAtOnce() {
        ControllerService service = service();
        service.tick();

        List<StatusSnapshot> late = listen(service);

        assertEquals(1, late.size());
        assertEquals(service.snapshot().reason(), late.get(0).reason());
    }

    @Test
    void aBrokenListenerHarmsNeitherTheServiceNorTheOtherListeners() {
        ControllerService service = service();
        service.addStatusListener(status -> { throw new IllegalStateException("bad listener"); });
        List<StatusSnapshot> healthy = listen(service);

        service.tick();

        assertEquals(1, healthy.size());
        assertEquals(1, logged("a status listener failed"));
        assertEquals(1, enforcer.delivered.size());
    }
}
