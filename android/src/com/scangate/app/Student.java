package com.scangate.app;

public class Student {
    public final String id;
    public final String name;
    public final String course;
    public final String yearLevel;
    public final String section;

    public Student(String id, String name, String course,
                   String yearLevel, String section) {
        this.id = id;
        this.name = name;
        this.course = course;
        this.yearLevel = yearLevel;
        this.section = section;
    }
}
