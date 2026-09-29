package com.scangate.app.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.scangate.app.logic.ClassClock;
import com.scangate.app.model.ScanLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Every read and write against the scan_logs table.
 *
 * A log row is never edited and never deleted by the app. If a scan happened,
 * it stays in the history, which is the whole point of a gate log.
 */
public class ScanLogDao {

    private final ScanGateDatabase database;

    public ScanLogDao(ScanGateDatabase database) {
        this.database = database;
    }

    /** Records one scan. */
    public void insert(String studentId, String storageStamp, String status, String note) {
        ContentValues values = new ContentValues();
        values.put("student_id", studentId);
        values.put("timestamp", storageStamp);
        values.put("status", status);
        values.put("note", note == null ? "" : note);
        database.getWritableDatabase().insert("scan_logs", null, values);
    }

    /** The newest scans first. */
    public List<ScanLog> findRecent(int limit) {
        List<ScanLog> logs = new ArrayList<ScanLog>();
        Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT id, student_id, timestamp, status, note "
                        + "FROM scan_logs ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (cursor.moveToNext()) {
                logs.add(new ScanLog(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4)));
            }
        } finally {
            cursor.close();
        }
        return logs;
    }

    /**
     * Counts scans on one day.
     *
     * @param day    "2026-09-27", from the first ten characters of a stamp
     * @param status one of the AttendanceStatus values, or "" for all of them
     */
    public int countOnDay(String day, String status) {
        String sql = "SELECT COUNT(*) FROM scan_logs WHERE substr(timestamp, 1, 10) = ?";
        String[] args;
        if (status == null || status.isEmpty()) {
            args = new String[]{day};
        } else {
            sql += " AND status = ?";
            args = new String[]{day, status};
        }

        Cursor cursor = database.getReadableDatabase().rawQuery(sql, args);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    /** Every scan ever recorded, for the history total on the home screen. */
    public int countAll() {
        Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM scan_logs", null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    /** Convenience: counts today's scans by reading today's date off the clock. */
    public int countToday(String status) {
        String today = ClassClock.dayOf(
                ClassClock.storageStamp(java.util.Calendar.getInstance()));
        return countOnDay(today, status);
    }
}
