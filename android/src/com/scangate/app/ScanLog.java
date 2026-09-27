package com.scangate.app;

public class ScanLog {
    public final long id;
    public final String studentId;
    public final String timestamp;
    public final String status;
    public final String note;

    public ScanLog(long id, String studentId, String timestamp,
                   String status, String note) {
        this.id = id;
        this.studentId = studentId;
        this.timestamp = timestamp;
        this.status = status;
        this.note = note;
    }
}
