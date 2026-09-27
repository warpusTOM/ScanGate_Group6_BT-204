package com.scangate.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** students + scan_logs, all in one local file on the phone. */
public class Db extends SQLiteOpenHelper {
    private static final String NAME = "scangate.db";
    private static final int VERSION = 1;

    public Db(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE students (" +
                "student_id TEXT PRIMARY KEY, " +
                "full_name TEXT NOT NULL, " +
                "course TEXT NOT NULL DEFAULT '', " +
                "year_level TEXT NOT NULL DEFAULT '', " +
                "section TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE scan_logs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "student_id TEXT NOT NULL, " +
                "timestamp TEXT NOT NULL, " +
                "note TEXT NOT NULL DEFAULT '', " +
                "status TEXT NOT NULL DEFAULT '')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    /** Load the bundled roster on first run. */
    public void seedIfEmpty(Context ctx) throws IOException {
        SQLiteDatabase db = getWritableDatabase();
        if (countStudents() > 0) return;
        BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("students.csv"), "UTF-8"));
        db.beginTransaction();
        try {
            String line = r.readLine();  // header
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length < 6 || c[0].trim().isEmpty()) continue;
                ContentValues v = new ContentValues();
                v.put("student_id", c[0].trim());
                v.put("full_name", c[1].trim());
                v.put("course", c[3].trim());
                v.put("year_level", c[4].trim());
                v.put("section", c[5].trim());
                db.insertWithOnConflict("students", null, v,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            r.close();
        }
    }

    public Student findStudent(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT student_id, full_name, course, year_level, section " +
                "FROM students WHERE student_id = ?",
                new String[]{id});
        try {
            if (!c.moveToFirst()) return null;
            return new Student(c.getString(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getString(4));
        } finally {
            c.close();
        }
    }

    public void logScan(String studentId, String timestamp, String status,
                        String note) {
        ContentValues v = new ContentValues();
        v.put("student_id", studentId);
        v.put("timestamp", timestamp);
        v.put("status", status);
        v.put("note", note);
        getWritableDatabase().insert("scan_logs", null, v);
    }

    public List<ScanLog> recentScans(int limit) {
        List<ScanLog> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, student_id, timestamp, status, note " +
                "FROM scan_logs ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(new ScanLog(c.getLong(0), c.getString(1),
                        c.getString(2), c.getString(3), c.getString(4)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int countStudents() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM students", null);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    /** scans today, optionally only one status ("" = all). */
    public int countScansToday(String day, String status) {
        String sql = "SELECT COUNT(*) FROM scan_logs " +
                "WHERE substr(timestamp, 1, 10) = ?";
        String[] args;
        if (status.isEmpty()) {
            args = new String[]{day};
        } else {
            sql += " AND status = ?";
            args = new String[]{day, status};
        }
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }
}
