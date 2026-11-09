package com.brainrot.controller.ui;

import com.brainrot.controller.policy.AppRule;
import com.brainrot.controller.policy.EmergencyUnlockResult;
import com.brainrot.controller.service.StatusSnapshot;
import com.brainrot.controller.service.StatusSnapshot.Reason;
import com.brainrot.controller.state.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusPresenterTest {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private final StatusPresenter presenter = new StatusPresenter(ZONE);

    private static Instant at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    private static final Instant NOW = at(11, 10, 15, 0);

    private static StatusSnapshot locked() {
        return new StatusSnapshot(Reason.LOCKED, null, true, true, at(11, 10, 14, 55), false);
    }

    private static StatusSnapshot solved() {
        return new StatusSnapshot(Reason.SOLVED_TODAY, at(11, 11, 4, 0), true, true, at(11, 10, 14, 55), false);
    }

    private static StatusSnapshot emergency() {
        return new StatusSnapshot(Reason.EMERGENCY_UNLOCK, at(11, 10, 16, 0), true, true, at(11, 10, 14, 55), false);
    }

    private static StatusSnapshot withEnforcer(StatusSnapshot s, Boolean reachable) {
        return new StatusSnapshot(s.reason(), s.unlockedUntil(), s.leetCodeReachable(), reachable, s.lastCheckAt(), s.dryRun());
    }

    // --- the headline and icon -----------------------------------------------------------

    @Test
    void lockedShowsTheLockAndTellsYouWhatToDo() {
        StatusPresenter.View view = presenter.present(locked(), NOW, null);

        assertTrue(view.locked());
        assertEquals("Locked: solve a LeetCode problem to unlock", view.headline());
    }

    @Test
    void solvedTodayShowsWhenTheUnlockEnds() {
        StatusPresenter.View view = presenter.present(solved(), NOW, null);

        assertFalse(view.locked());
        assertEquals("Unlocked until tomorrow 04:00 (solved today)", view.headline());
    }

    @Test
    void anEmergencyUnlockSaysSoAndShowsItsEnd() {
        assertEquals("Emergency unlock until 16:00", presenter.present(emergency(), NOW, null).headline());
    }

    @Test
    void timesReadNaturallyTodayTomorrowAndLater() {
        assertEquals("16:30", presenter.time(at(11, 10, 16, 30), NOW));
        assertEquals("tomorrow 04:00", presenter.time(at(11, 11, 4, 0), NOW));
        assertEquals("Sat 14 Nov 04:00", presenter.time(at(11, 14, 4, 0), NOW));
    }

    @Test
    void justAfterMidnightStillCountsAsTheNextCalendarDay() {
        Instant lateNight = at(11, 10, 23, 30);
        assertEquals("tomorrow 00:15", presenter.time(at(11, 11, 0, 15), lateNight));
    }

    // --- the detail lines ----------------------------------------------------------------

    @Test
    void detailLinesReportBothConnections() {
        assertEquals(java.util.List.of("LeetCode: reachable, checked 14:55", "Enforcer: connected"),
                presenter.present(locked(), NOW, null).details());
    }

    @Test
    void unknownAndBrokenConnectionsAreSpelledOut() {
        StatusSnapshot starting = new StatusSnapshot(Reason.LOCKED, null, null, null, null, false);
        assertEquals(java.util.List.of("LeetCode: not checked yet", "Enforcer: connecting..."),
                presenter.present(starting, NOW, null).details());

        StatusSnapshot broken = new StatusSnapshot(Reason.LOCKED, null, false, false, null, false);
        var details = presenter.present(broken, NOW, null).details();
        assertTrue(details.get(0).contains("unreachable"));
        assertTrue(details.get(1).contains("enforcer serve"));
    }

    @Test
    void dryRunIsAlwaysVisibleSoNobodyThinksBlockingIsOn() {
        StatusSnapshot dry = new StatusSnapshot(Reason.LOCKED, null, true, true, null, true);

        assertTrue(presenter.present(dry, NOW, null).details().contains("Dry run: nothing is really closed"));
        assertFalse(presenter.present(locked(), NOW, null).details().stream().anyMatch(l -> l.startsWith("Dry run")));
    }

    // --- when to notify ------------------------------------------------------------------

    @Test
    void thereIsNoPopUpTheFirstTimeAStatusAppears() {
        assertNull(presenter.present(locked(), NOW, null).notification());
        assertNull(presenter.present(solved(), NOW, null).notification());
    }

    @Test
    void solvingAProblemCelebratesAndSaysUntilWhen() {
        assertEquals("Solved. Unlocked until tomorrow 04:00.", presenter.present(solved(), NOW, locked()).notification());
    }

    @Test
    void anEmergencyUnlockIsAnnouncedAsSuch() {
        assertEquals("Emergency unlock. Unlocked until 16:00.", presenter.present(emergency(), NOW, locked()).notification());
    }

    @Test
    void whenTheUnlockEndsYouAreToldItIsLockedAgain() {
        assertEquals("Locked again. Solve a LeetCode problem to unlock.", presenter.present(locked(), NOW, solved()).notification());
        assertEquals("Locked again. Solve a LeetCode problem to unlock.", presenter.present(locked(), NOW, emergency()).notification());
    }

    @Test
    void losingAndRegainingTheEnforcerIsWorthAPopUp() {
        StatusSnapshot lost = withEnforcer(locked(), false);

        assertTrue(presenter.present(lost, NOW, locked()).notification().contains("not reachable"));
        assertEquals("The enforcer is reachable again.", presenter.present(locked(), NOW, lost).notification());
    }

    @Test
    void thereIsNoPopUpForChangesNobodyNeedsToHearAbout() {
        // The enforcer connecting for the very first time, and LeetCode being checked again.
        StatusSnapshot connecting = withEnforcer(locked(), null);
        assertNull(presenter.present(locked(), NOW, connecting).notification());

        StatusSnapshot rechecked = new StatusSnapshot(Reason.LOCKED, null, true, true, at(11, 10, 15, 0), false);
        assertNull(presenter.present(rechecked, NOW, locked()).notification());

        // Switching between two kinds of unlock does not re-announce.
        assertNull(presenter.present(emergency(), NOW, solved()).notification());
    }

    @Test
    void anUnlockChangeOutranksAConnectionChange() {
        StatusSnapshot lostWhileSolving = withEnforcer(solved(), false);

        assertTrue(presenter.present(lostWhileSolving, NOW, withEnforcer(locked(), true)).notification().startsWith("Solved."));
    }

    // --- emergency unlock answers ---------------------------------------------------------

    @Test
    void everyEmergencyUnlockAnswerHasPlainWords() {
        assertEquals("Emergency unlock until 16:00.", presenter.describe(new EmergencyUnlockResult.Granted(State.empty(), at(11, 10, 16, 0)), NOW));
        assertTrue(presenter.describe(new EmergencyUnlockResult.WrongPhrase(), NOW).contains("exactly"));
        assertTrue(presenter.describe(new EmergencyUnlockResult.NotNeeded(), NOW).contains("already solved"));
        assertEquals("An emergency unlock is already running until 15:30.",
                presenter.describe(new EmergencyUnlockResult.AlreadyActive(at(11, 10, 15, 30)), NOW));
        assertEquals("No emergency unlocks left. The next one is available Tue 17 Nov 20:00.",
                presenter.describe(new EmergencyUnlockResult.NoneLeft(Optional.of(at(11, 17, 20, 0))), NOW));
        assertEquals("Emergency unlocks are switched off in the config.",
                presenter.describe(new EmergencyUnlockResult.NoneLeft(Optional.empty()), NOW));
    }

    // --- the "Blocked applications" menu -------------------------------------------------

    private static AppRule byName(String label, String processName) {
        return new AppRule(label, "", processName, "", List.of());
    }

    @Test
    void blockedAppsListsEachLabelOnceInConfigOrder() {
        List<AppRule> rules = List.of(
                byName("Valorant", "VALORANT.exe"),
                byName("Valorant", "VALORANT-Win64-Shipping.exe"),
                byName("League of Legends", "LeagueClient.exe"),
                byName("Final Fantasy XIV", "ffxiv_dx11.exe"),
                byName("League of Legends", "League of Legends.exe"));

        assertEquals(List.of("Valorant", "League of Legends", "Final Fantasy XIV"), presenter.blockedApps(rules));
    }

    @Test
    void anUnlabelledRuleIsShownByWhatItMatches() {
        List<AppRule> rules = List.of(
                byName("", "steam.exe"),
                new AppRule(" ", "com.hnc.Discord", "", "", List.of()),
                new AppRule("", "", "", "C:\\Games\\Odd\\", List.of()),
                new AppRule("", "", "", "", List.of("com.example.game", "--username")));

        assertEquals(List.of("steam.exe", "com.hnc.Discord", "C:\\Games\\Odd\\", "com.example.game --username"),
                presenter.blockedApps(rules));
    }

    @Test
    void noRulesMeansAnEmptyList() {
        assertTrue(presenter.blockedApps(List.of()).isEmpty());
    }
}
