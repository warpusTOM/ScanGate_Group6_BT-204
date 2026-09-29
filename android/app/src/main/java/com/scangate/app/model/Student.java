package com.scangate.app.model;

/**
 * One registered student, exactly as it comes out of the students table.
 *
 * Plain data holder. It does not touch the database and it does not touch
 * Android, so it can be tested on a normal computer.
 */
public class Student {

    public final String studentId;
    public final String fullName;
    public final String course;
    public final String yearLevel;
    public final String section;

    public Student(String studentId, String fullName, String course,
                   String yearLevel, String section) {
        this.studentId = studentId == null ? "" : studentId.trim();
        this.fullName = fullName == null ? "" : fullName.trim();
        this.course = course == null ? "" : course.trim();
        this.yearLevel = yearLevel == null ? "" : yearLevel.trim();
        this.section = section == null ? "" : section.trim();
    }

    /** "BT-204", or "(no section on file)" when the row is incomplete. */
    public String sectionLabel() {
        if (section.length() > 0) return section;
        StringBuilder joined = new StringBuilder();
        if (course.length() > 0) joined.append(course);
        if (yearLevel.length() > 0) {
            if (joined.length() > 0) joined.append(' ');
            joined.append(yearLevel);
        }
        return joined.length() > 0 ? joined.toString() : "(no section on file)";
    }

    @Override
    public String toString() {
        return fullName + " (" + studentId + ")";
    }
}
