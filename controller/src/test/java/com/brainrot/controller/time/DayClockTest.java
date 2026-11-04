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
}
