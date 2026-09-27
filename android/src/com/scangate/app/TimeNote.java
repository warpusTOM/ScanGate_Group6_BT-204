package com.scangate.app;

import java.util.Calendar;

/** EARLY / ON TIME / LATE against the class start time.
 *  Default window: class 08:00, on time from 07:45 to 08:10. */
public final class TimeNote {
    public static final String EARLY = "EARLY";
    public static final String ON_TIME = "ON TIME";
    public static final String LATE = "LATE";

    private TimeNote() {}

    public static String classify(Calendar now, int startH, int startM,
                                  int earlyBeforeMin, int lateAfterMin) {
        Calendar start = (Calendar) now.clone();
        start.set(Calendar.HOUR_OF_DAY, startH);
        start.set(Calendar.MINUTE, startM);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        long nowMs = now.getTimeInMillis();
        long windowFrom = start.getTimeInMillis() - earlyBeforeMin * 60_000L;
        long windowTo = start.getTimeInMillis() + lateAfterMin * 60_000L;

        if (nowMs < windowFrom) return EARLY;
        if (nowMs <= windowTo) return ON_TIME;
        return LATE;
    }
}
