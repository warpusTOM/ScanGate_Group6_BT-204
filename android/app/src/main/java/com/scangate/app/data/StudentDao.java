package com.scangate.app.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.scangate.app.model.Student;

import java.util.List;

/**
 * Every read and write against the students table.
 *
 * Keeping the SQL in here means the screens never build a query string, so
 * there is exactly one place to look when a lookup behaves oddly.
 */
public class StudentDao {

    private final ScanGateDatabase database;

    public StudentDao(ScanGateDatabase database) {
        this.database = database;
    }

    /**
     * Looks up one student by number.
     *
     * @return the student, or null when the number is not in the roster
     */
    public Student findById(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) return null;

        Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT student_id, full_name, course, year_level, section "
                        + "FROM students WHERE student_id = ?",
                new String[]{studentId.trim()});
        try {
            if (!cursor.moveToFirst()) return null;
            return new Student(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4));
        } finally {
            cursor.close();
        }
    }

    /** How many students are loaded right now. */
    public int countAll() {
        Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM students", null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    /**
     * Writes a whole roster in one transaction.
     *
     * REPLACE means importing the same file twice updates the rows instead of
     * failing on a duplicate student number, which is what the portal does too.
     *
     * @return how many rows were written
     */
    public int replaceAll(List<Student> students) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < students.size(); i++) {
                Student student = students.get(i);
                ContentValues values = new ContentValues();
                values.put("student_id", student.studentId);
                values.put("full_name", student.fullName);
                values.put("course", student.course);
                values.put("year_level", student.yearLevel);
                values.put("section", student.section);
                db.insertWithOnConflict("students", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
            return students.size();
        } finally {
            db.endTransaction();
        }
    }
}
