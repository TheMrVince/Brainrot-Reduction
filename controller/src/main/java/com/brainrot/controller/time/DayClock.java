package com.brainrot.controller.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Defines "today" as starting at a fixed local hour (e.g. 4 AM) rather than midnight. */
public final class DayClock {
    private final ZoneId zone;
    private final int dayStartHour;

    public DayClock(ZoneId zone, int dayStartHour) {
        if (dayStartHour < 0 || dayStartHour > 23) {
            throw new IllegalArgumentException("dayStartHour must be 0-23: " + dayStartHour);
        }
        this.zone = zone;
        this.dayStartHour = dayStartHour;
    }

    /** The most recent day start at or before {@code now}. */
    public Instant dayStart(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate date = local.toLocalDate();
        ZonedDateTime start = date.atTime(dayStartHour, 0).atZone(zone);
        if (start.isAfter(local)) {
            start = date.minusDays(1).atTime(dayStartHour, 0).atZone(zone);
        }
        return start.toInstant();
    }

    public boolean isToday(Instant moment, Instant now) {
        return !moment.isBefore(dayStart(now)) && !moment.isAfter(now);
    }
}
