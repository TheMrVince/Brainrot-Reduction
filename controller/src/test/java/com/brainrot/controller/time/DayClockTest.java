package com.brainrot.controller.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayClockTest {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private final DayClock clock = new DayClock(ZONE, 4);

    private static Instant at(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    @Test
    void afterDayStartUsesTodays4am() {
        assertEquals(at(11, 5, 4, 0), clock.dayStart(at(11, 5, 4, 1)));
        assertEquals(at(11, 5, 4, 0), clock.dayStart(at(11, 5, 23, 59)));
    }

    @Test
    void beforeDayStartStillBelongsToPreviousDay() {
        assertEquals(at(11, 4, 4, 0), clock.dayStart(at(11, 5, 3, 59)));
        assertEquals(at(11, 4, 4, 0), clock.dayStart(at(11, 5, 0, 0)));
    }

    @Test
    void submissionAt1amCountsForTheDayStartedYesterday() {
        Instant now = at(11, 5, 2, 0);
        assertTrue(clock.isToday(at(11, 4, 22, 0), now));
        assertFalse(clock.isToday(at(11, 4, 3, 0), now));
    }

    @Test
    void futureMomentsAreNotToday() {
        assertFalse(clock.isToday(at(11, 5, 12, 0), at(11, 5, 10, 0)));
    }

    @Test
    void nextDayStartIsTheFollowing4am() {
        assertEquals(at(11, 6, 4, 0), clock.nextDayStart(at(11, 5, 4, 0)));
        assertEquals(at(11, 6, 4, 0), clock.nextDayStart(at(11, 5, 23, 59)));
        assertEquals(at(11, 5, 4, 0), clock.nextDayStart(at(11, 5, 3, 59)));  // still "yesterday"
    }

    @Test
    void daysFollowTheCalendarAcrossDaylightSavingChanges() {
        // Spring forward 2026-03-08: the day that starts on the 7th is 23 hours long.
        Instant now = ZonedDateTime.of(2026, 3, 7, 10, 0, 0, 0, ZONE).toInstant();
        assertEquals(ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, ZONE).toInstant(), clock.nextDayStart(now));
        assertEquals(23, java.time.Duration.between(clock.dayStart(now), clock.nextDayStart(now)).toHours());

        // Fall back 2026-11-01: the day that starts on 10-31 is 25 hours long.
        Instant autumn = ZonedDateTime.of(2026, 10, 31, 10, 0, 0, 0, ZONE).toInstant();
        assertEquals(25, java.time.Duration.between(clock.dayStart(autumn), clock.nextDayStart(autumn)).toHours());
    }
}
