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
        return startOf(currentDay(now));
    }

    /** The first day start after {@code now}: when today ends. Follows the calendar, so a day can be 23 or 25 hours. */
    public Instant nextDayStart(Instant now) {
        return startOf(currentDay(now).plusDays(1));
    }

    public boolean isToday(Instant moment, Instant now) {
        return !moment.isBefore(dayStart(now)) && !moment.isAfter(now);
    }

    /** The calendar date whose 4 AM (or configured hour) started the day that contains {@code now}. */
    private LocalDate currentDay(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate date = local.toLocalDate();
        return startOf(date).isAfter(now) ? date.minusDays(1) : date;
    }

    private Instant startOf(LocalDate date) {
        return date.atTime(dayStartHour, 0).atZone(zone).toInstant();
    }
}
