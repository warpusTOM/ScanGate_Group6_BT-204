package com.scangate.app.model;

/**
 * When class starts, and how wide the "on time" window is around it.
 *
 * Defaults match the laptop portal: class at 08:00, on time from 07:45 to
 * 08:10. The values are stored in the settings table so they survive a
 * restart, and the admin can change them from the home screen.
 */
public final class ClassTimeSettings {

    public static final int DEFAULT_START_HOUR = 8;
    public static final int DEFAULT_START_MINUTE = 0;
    public static final int DEFAULT_EARLY_BEFORE_MINUTES = 15;
    public static final int DEFAULT_LATE_AFTER_MINUTES = 10;

    public final int startHour;
    public final int startMinute;
    public final int earlyBeforeMinutes;
    public final int lateAfterMinutes;

    public ClassTimeSettings(int startHour, int startMinute,
                             int earlyBeforeMinutes, int lateAfterMinutes) {
        this.startHour = clamp(startHour, 0, 23);
        this.startMinute = clamp(startMinute, 0, 59);
        // negative windows make no sense, so they get pushed back to zero
        this.earlyBeforeMinutes = Math.max(0, earlyBeforeMinutes);
        this.lateAfterMinutes = Math.max(0, lateAfterMinutes);
    }

    public static ClassTimeSettings defaults() {
        return new ClassTimeSettings(DEFAULT_START_HOUR, DEFAULT_START_MINUTE,
                DEFAULT_EARLY_BEFORE_MINUTES, DEFAULT_LATE_AFTER_MINUTES);
    }

    /** "08:00" - the form the settings table and the admin box both use. */
    public String startTimeText() {
        return twoDigits(startHour) + ":" + twoDigits(startMinute);
    }

    public ClassTimeSettings withStartTime(String hhmm) {
        int[] parsed = parseClock(hhmm);
        if (parsed == null) return this;
        return new ClassTimeSettings(parsed[0], parsed[1],
                earlyBeforeMinutes, lateAfterMinutes);
    }

    public ClassTimeSettings withWindow(int earlyBefore, int lateAfter) {
        return new ClassTimeSettings(startHour, startMinute, earlyBefore, lateAfter);
    }

    /**
     * Reads "8:5", "08:05" or "08:05:00" into {hour, minute}.
     * Returns null when the text is not a time at all, so the caller can
     * show an error instead of quietly saving nonsense.
     */
    public static int[] parseClock(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split(":");
        if (parts.length < 2) return null;
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return new int[]{hour, minute};
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static int clamp(int value, int low, int high) {
        if (value < low) return low;
        if (value > high) return high;
        return value;
    }
}
