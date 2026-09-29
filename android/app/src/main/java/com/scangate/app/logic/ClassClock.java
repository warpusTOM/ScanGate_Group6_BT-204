package com.scangate.app.logic;

import com.scangate.app.model.ClassTimeSettings;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * All the time maths in one place.
 *
 * Two jobs:
 *   1. decide whether a scan is EARLY, ON TIME or LATE
 *   2. format times for the screen in 12-hour form
 *
 * The phone's clock is what gets used. There is no internet check and no
 * server time, which is fine because the whole app runs on one phone.
 */
public final class ClassClock {

    private static final String STORAGE_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final long ONE_MINUTE_IN_MS = 60000L;

    private ClassClock() {
    }

    /**
     * Compares the scan time against the class start time.
     *
     *   scanned more than earlyBefore minutes before start  -> EARLY
     *   inside start - earlyBefore .. start + lateAfter     -> ON TIME
     *   later than that                                     -> LATE
     */
    public static String classify(Calendar scannedAt, ClassTimeSettings window) {
        Calendar classStart = (Calendar) scannedAt.clone();
        classStart.set(Calendar.HOUR_OF_DAY, window.startHour);
        classStart.set(Calendar.MINUTE, window.startMinute);
        classStart.set(Calendar.SECOND, 0);
        classStart.set(Calendar.MILLISECOND, 0);

        long scanTime = scannedAt.getTimeInMillis();
        long windowOpens = classStart.getTimeInMillis()
                - window.earlyBeforeMinutes * ONE_MINUTE_IN_MS;
        long windowCloses = classStart.getTimeInMillis()
                + window.lateAfterMinutes * ONE_MINUTE_IN_MS;

        if (scanTime < windowOpens) return AttendanceStatus.EARLY;
        if (scanTime <= windowCloses) return AttendanceStatus.ON_TIME;
        return AttendanceStatus.LATE;
    }

    /** The 24-hour form that goes into the database: "2026-09-27 20:27:28". */
    public static String storageStamp(Calendar when) {
        return new SimpleDateFormat(STORAGE_PATTERN, Locale.US).format(when.getTime());
    }

    /**
     * Turns a stored clock time into 12-hour form: "20:27:28" -> "8:27:28 PM".
     *
     * Storage deliberately stays 24-hour. The "scans today" query slices the
     * first ten characters out of the stored stamp, so if we saved "8:27:28 PM"
     * the date would disappear and the counters would break.
     */
    public static String to12Hour(String clockTime) {
        if (clockTime == null) return "";
        String text = clockTime.trim();
        int colon = text.indexOf(':');
        if (colon < 1) return text;

        int hour;
        try {
            hour = Integer.parseInt(text.substring(0, colon));
        } catch (NumberFormatException notAClock) {
            return text;
        }

        int hour12 = hour % 12;
        if (hour12 == 0) hour12 = 12;
        return hour12 + text.substring(colon) + (hour < 12 ? " AM" : " PM");
    }

    /** Same as above, but takes a whole stamp: "2026-09-27 20:27:28". */
    public static String stampTo12Hour(String storageStamp) {
        if (storageStamp == null || storageStamp.length() < 19) return storageStamp;
        return storageStamp.substring(0, 11) + to12Hour(storageStamp.substring(11));
    }

    /** The date part of a stored stamp, used by the "scans today" query. */
    public static String dayOf(String storageStamp) {
        if (storageStamp == null || storageStamp.length() < 10) return "";
        return storageStamp.substring(0, 10);
    }
}
