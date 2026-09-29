package com.scangate.app.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.scangate.app.model.ClassTimeSettings;

/**
 * Reads and writes the settings table (class start time and window sizes).
 *
 * The three values are stored as plain text under the keys start_time,
 * early_before and late_after. Anything unreadable falls back to the default,
 * so a corrupted setting can never stop the app from scanning.
 */
public class SettingsDao {

    public static final String KEY_START_TIME = "start_time";
    public static final String KEY_EARLY_BEFORE = "early_before";
    public static final String KEY_LATE_AFTER = "late_after";

    private final ScanGateDatabase database;

    public SettingsDao(ScanGateDatabase database) {
        this.database = database;
    }

    /** The window currently in effect, with defaults filled in for anything missing. */
    public ClassTimeSettings load() {
        ClassTimeSettings defaults = ClassTimeSettings.defaults();

        int[] clock = ClassTimeSettings.parseClock(
                read(KEY_START_TIME, defaults.startTimeText()));
        if (clock == null) {
            clock = new int[]{defaults.startHour, defaults.startMinute};
        }

        return new ClassTimeSettings(
                clock[0],
                clock[1],
                readInt(KEY_EARLY_BEFORE, defaults.earlyBeforeMinutes),
                readInt(KEY_LATE_AFTER, defaults.lateAfterMinutes));
    }

    public void save(ClassTimeSettings settings) {
        write(KEY_START_TIME, settings.startTimeText());
        write(KEY_EARLY_BEFORE, String.valueOf(settings.earlyBeforeMinutes));
        write(KEY_LATE_AFTER, String.valueOf(settings.lateAfterMinutes));
    }

    private String read(String key, String fallback) {
        Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT value FROM settings WHERE key = ?", new String[]{key});
        try {
            if (cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
            return fallback;
        } finally {
            cursor.close();
        }
    }

    private int readInt(String key, int fallback) {
        try {
            return Integer.parseInt(read(key, String.valueOf(fallback)));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private void write(String key, String value) {
        SQLiteDatabase db = database.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        db.insertWithOnConflict("settings", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }
}
