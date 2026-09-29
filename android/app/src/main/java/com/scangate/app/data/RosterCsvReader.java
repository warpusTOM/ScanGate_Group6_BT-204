package com.scangate.app.data;

import com.scangate.app.model.Student;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a student roster out of a CSV file.
 *
 * It is forgiving about the column names, because the file usually comes from
 * the registrar or from the school portal and nobody wants to hand-edit it.
 * These headers all work:
 *
 *   Student No · ID · student_id · LRN · ID Number
 *   Name · Full Name · student_name
 *   Email · Gmail · E-mail
 *   Course · Program · Strand
 *   Year · Grade · Year Level
 *   Section · Sec · Block
 *
 * The school portal's own credentials export also works untouched:
 *
 *   section,student_no,surname,first_name,access_key
 *
 * with three small rules for it:
 *   full name    = first name + surname
 *   student id   = student_no, or the access key when student_no is blank
 *   course, year = pulled out of the section (BT-204 becomes BT and 2)
 *
 * No Android classes are used in here, so this file can be tested on a
 * normal computer with a plain CSV string.
 */
public final class RosterCsvReader {

    /** canonical field name, then every header spelling that means it. */
    private static final String[][] HEADER_ALIASES = {
            {"student_id", "student_id", "id", "student_no", "student no", "student no.",
                    "student_number", "student number", "id_no", "id no", "id number", "lrn"},
            {"full_name", "full_name", "fullname", "full name", "name",
                    "student_name", "student name"},
            {"first_name", "first_name", "firstname", "first name",
                    "given name", "given_name"},
            {"surname", "surname", "last_name", "lastname", "last name",
                    "family_name", "family name"},
            {"access_key", "access_key", "access key", "key"},
            {"gmail", "gmail", "email", "e-mail", "gmail_address", "gmail address",
                    "email_address", "email address"},
            {"course", "course", "program", "strand", "track"},
            {"year_level", "year_level", "year level", "year", "yr", "grade",
                    "grade_level", "grade level"},
            {"section", "section", "sec", "block", "class"},
    };

    private RosterCsvReader() {
    }

    public static List<Student> read(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder wholeFile = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            wholeFile.append(line).append('\n');
        }
        return read(wholeFile.toString());
    }

    public static List<Student> read(String csvText) throws IOException {
        List<String> lines = splitLines(csvText);
        if (lines.isEmpty()) throw new IOException("The roster file is empty.");

        List<String> headers = splitCsvLine(lines.get(0));
        Map<String, Integer> columnOf = mapColumns(headers);

        if (!columnOf.containsKey("student_id") && !columnOf.containsKey("access_key")) {
            throw new IOException("The roster file has no student number column. "
                    + "Accepted headers: student_id, student_no, id, lrn, access_key.");
        }
        if (!columnOf.containsKey("full_name") && !columnOf.containsKey("first_name")) {
            throw new IOException("The roster file has no name column. "
                    + "Accepted headers: full_name, name, first_name.");
        }

        List<Student> students = new ArrayList<Student>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = splitCsvLine(lines.get(i));

            String studentId = value(row, columnOf, "student_id");
            String fullName = value(row, columnOf, "full_name");

            // Portal export shape: the name arrives in two columns.
            if (fullName.isEmpty()) {
                String first = value(row, columnOf, "first_name");
                String last = value(row, columnOf, "surname");
                fullName = titleCase((first + " " + last).trim());
            }

            // Portal export shape: fall back to the access key.
            if (studentId.isEmpty()) studentId = value(row, columnOf, "access_key");
            if (studentId.isEmpty()) continue;   // nothing to identify them by

            String course = value(row, columnOf, "course");
            String yearLevel = value(row, columnOf, "year_level");
            String section = value(row, columnOf, "section");

            // Derive course and year from the section when the file has neither.
            if (!section.isEmpty() && course.isEmpty()) {
                int dash = section.indexOf('-');
                if (dash > 0) {
                    course = section.substring(0, dash);
                    if (yearLevel.isEmpty()) {
                        yearLevel = section.substring(dash + 1, dash + 2);
                    }
                }
            }

            students.add(new Student(studentId, fullName, course, yearLevel, section));
        }
        return students;
    }

    /** canonical field -> column position, using the first header that matches. */
    private static Map<String, Integer> mapColumns(List<String> headers) {
        Map<String, Integer> lowerToIndex = new HashMap<String, Integer>();
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.get(i).trim().toLowerCase();
            if (!lowerToIndex.containsKey(name)) lowerToIndex.put(name, Integer.valueOf(i));
        }

        Map<String, Integer> columnOf = new HashMap<String, Integer>();
        for (int field = 0; field < HEADER_ALIASES.length; field++) {
            String[] group = HEADER_ALIASES[field];
            for (int alias = 1; alias < group.length; alias++) {
                Integer index = lowerToIndex.get(group[alias]);
                if (index != null) {
                    columnOf.put(group[0], index);
                    break;
                }
            }
        }
        return columnOf;
    }

    private static String value(List<String> row, Map<String, Integer> columnOf, String field) {
        Integer index = columnOf.get(field);
        if (index == null || index.intValue() >= row.size()) return "";
        return row.get(index.intValue()).trim();
    }

    /** Splits on real newlines only, ignoring the ones inside quoted fields. */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>();
        String cleaned = text;
        if (cleaned.length() > 0 && cleaned.charAt(0) == '\uFEFF') {
            cleaned = cleaned.substring(1);   // Excel likes to add a byte order mark
        }
        String[] raw = cleaned.split("\r\n|\n|\r");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].trim().isEmpty()) lines.add(raw[i]);
        }
        return lines;
    }

    /**
     * Splits one CSV line, honouring double quotes so a name containing a
     * comma does not break the row. "" inside quotes means one literal quote.
     */
    static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean insideQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (insideQuotes) {
                if (c == '"') {
                    boolean doubled = i + 1 < line.length() && line.charAt(i + 1) == '"';
                    if (doubled) {
                        current.append('"');
                        i++;
                    } else {
                        insideQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                insideQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /** Capitalises each word: "juan dela cruz" -> "Juan Dela Cruz". */
    static String titleCase(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean startOfWord = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                out.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
                startOfWord = false;
            } else {
                out.append(c);
                startOfWord = true;
            }
        }
        return out.toString();
    }
}
