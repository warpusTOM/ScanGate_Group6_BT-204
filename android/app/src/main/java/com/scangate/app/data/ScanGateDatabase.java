package com.scangate.app.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * The one and only place that creates tables.
 *
 * Three tables live in the phone's private database file, scangate.db:
 *
 *   students    the roster, copied out of assets/students.csv on first run
 *   scan_logs   one row per scan, kept forever so the history is auditable
 *   settings    class start time and the two window sizes
 *
 * Nothing here ever leaves the phone. No account, no sync, no internet.
 *
 * Column names match the laptop portal's database on purpose, so a roster
 * or a log file can be moved between the two without a conversion step.
 */
public class ScanGateDatabase extends SQLiteOpenHelper {

    public static final String FILE_NAME = "scangate.db";

    /**
     * Bump this number whenever a CREATE statement below changes, and add the
     * matching step in onUpgrade. Version 1 shipped without the settings table.
     */
    public static final int SCHEMA_VERSION = 2;

    public static final String CREATE_STUDENTS =
            "CREATE TABLE students ("
                    + "student_id TEXT PRIMARY KEY, "
                    + "full_name  TEXT NOT NULL, "
                    + "gmail      TEXT NOT NULL DEFAULT '', "
                    + "course     TEXT NOT NULL DEFAULT '', "
                    + "year_level TEXT NOT NULL DEFAULT '', "
                    + "section    TEXT NOT NULL DEFAULT ''"
                    + ")";

    public static final String CREATE_SCAN_LOGS =
            "CREATE TABLE scan_logs ("
                    + "id         INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "student_id TEXT NOT NULL, "
                    + "timestamp  TEXT NOT NULL, "
                    + "note       TEXT NOT NULL DEFAULT '', "
                    + "status     TEXT NOT NULL DEFAULT ''"
                    + ")";

    public static final String CREATE_SETTINGS =
            "CREATE TABLE settings ("
                    + "key   TEXT PRIMARY KEY, "
                    + "value TEXT NOT NULL"
                    + ")";

    /**
     * The "scans today" counter filters on substr(timestamp, 1, 10), which
     * cannot use an index, but the recent-scans list is ordered by timestamp
     * and that one can.
     */
    public static final String CREATE_LOG_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_scan_logs_timestamp "
                    + "ON scan_logs (timestamp)";

    public ScanGateDatabase(Context context) {
        super(context, FILE_NAME, null, SCHEMA_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_STUDENTS);
        db.execSQL(CREATE_SCAN_LOGS);
        db.execSQL(CREATE_SETTINGS);
        db.execSQL(CREATE_LOG_TIME_INDEX);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Upgrading from the first release, which had no settings table.
        if (oldVersion < 2) {
            db.execSQL(CREATE_SETTINGS);
            db.execSQL(CREATE_LOG_TIME_INDEX);
        }
    }
}
