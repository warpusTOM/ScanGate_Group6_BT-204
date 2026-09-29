package com.scangate.app.model;

/**
 * One line of the scan history: who was scanned, when, and what the note was.
 *
 * The timestamp is always stored in 24-hour ISO form ("2026-09-27 20:27:28").
 * Only the copy shown on screen is turned into 12-hour time, because the
 * "how many scans today" query slices the date straight out of this string.
 */
public class ScanLog {

    public final long rowId;
    public final String studentId;
    public final String timestamp;
    public final String status;
    public final String note;

    public ScanLog(long rowId, String studentId, String timestamp,
                   String status, String note) {
        this.rowId = rowId;
        this.studentId = studentId == null ? "" : studentId;
        this.timestamp = timestamp == null ? "" : timestamp;
        this.status = status == null ? "" : status;
        this.note = note == null ? "" : note;
    }

    @Override
    public String toString() {
        return timestamp + " " + studentId + " " + status;
    }
}
