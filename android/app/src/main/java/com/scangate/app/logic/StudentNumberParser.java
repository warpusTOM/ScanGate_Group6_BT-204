package com.scangate.app.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Digs the student number out of whatever the barcode actually said.
 *
 * School ID barcodes are not made to one standard. Depending on who printed
 * them, the same card can hold any of these:
 *
 *   20253152                                plain number
 *   STU20253152                             prefix glued on
 *   2025-3152                               separator in the middle
 *   ALIMEN                                  letter-only code (some rows are like this)
 *   {"student_id":"20253152","name":"..."}  JSON, usually in a QR code
 *   https://cscqcph.com/verify?id=20253152  a link with the number in the query
 *
 * So instead of guessing one format, we pull out a short list of candidate
 * numbers, best first, and let the database lookup try them in order. The
 * first one that matches a real student wins.
 */
public final class StudentNumberParser {

    /** Field names that usually sit right before the number in JSON or a URL. */
    private static final String[] KEY_NAMES = {
            "student_no", "student_number", "studentnumber", "student_id", "studentid",
            "id_number", "idnumber", "id_no", "idno", "school_id", "schoolid",
            "lrn", "sid", "id"
    };

    /** key : value  or  key = value, with the quotes and spacing optional. */
    private static final Pattern KEYED_VALUE;

    /** A run of digits long enough to be a real number, not a year or a room. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{4,}");

    /** A short plain code, letters and digits, e.g. "ALIMEN" or "STU20253152". */
    private static final Pattern PLAIN_CODE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,29}");

    /** Stops a runaway QR payload from producing a hundred candidates. */
    private static final int MAX_CANDIDATES = 8;

    static {
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < KEY_NAMES.length; i++) {
            if (i > 0) keys.append('|');
            keys.append(Pattern.quote(KEY_NAMES[i]));
        }
        KEYED_VALUE = Pattern.compile(
                "(?i)[\"']?(" + keys + ")[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9][A-Za-z0-9._-]*)");
    }

    private StudentNumberParser() {
    }

    /**
     * Candidate student numbers, best guess first. Never null, but can be empty
     * when the text has nothing usable in it.
     */
    public static List<String> candidates(String rawText) {
        List<String> ordered = new ArrayList<String>();
        if (rawText == null) return ordered;

        String text = cleanUp(rawText);
        if (text.isEmpty()) return ordered;

        LinkedHashSet<String> seen = new LinkedHashSet<String>();

        // 1. Anything sitting behind a field name. This is the most reliable
        //    shape, so it goes to the front of the queue.
        Matcher keyed = KEYED_VALUE.matcher(text);
        while (keyed.find() && ordered.size() < MAX_CANDIDATES) {
            addCandidate(ordered, seen, keyed.group(2));
        }

        // 2. The whole thing, when it is already just a short code.
        Matcher plain = PLAIN_CODE.matcher(text);
        if (plain.matches()) {
            addCandidate(ordered, seen, text);
        }

        // 3. Every long digit run, longest first. On a card that prints the
        //    number twice, the longer run is the full student number.
        List<String> runs = new ArrayList<String>();
        Matcher digits = DIGIT_RUN.matcher(text);
        while (digits.find()) runs.add(digits.group());
        Collections.sort(runs, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return right.length() - left.length();
            }
        });
        for (int i = 0; i < runs.size() && ordered.size() < MAX_CANDIDATES; i++) {
            addCandidate(ordered, seen, runs.get(i));
        }

        // 4. Last resort: a short single word with no digits, which is how some
        //    rows are keyed ("ALIMEN"). A whole name with spaces in it is not a
        //    student number, so it is deliberately left out.
        if (ordered.isEmpty() && text.length() <= 30 && text.indexOf(' ') < 0) {
            addCandidate(ordered, seen, text);
        }

        return ordered;
    }

    /** The single best guess, or null when there is nothing to guess with. */
    public static String bestGuess(String rawText) {
        List<String> all = candidates(rawText);
        return all.isEmpty() ? null : all.get(0);
    }

    private static void addCandidate(List<String> ordered, LinkedHashSet<String> seen,
                                     String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;
        if (trimmed.length() > 40) return;
        if (seen.add(trimmed.toUpperCase())) {
            ordered.add(trimmed);
        }
    }

    /** Strips control characters, BOMs and stray line breaks out of a scan. */
    private static String cleanUp(String rawText) {
        StringBuilder cleaned = new StringBuilder(rawText.length());
        for (int i = 0; i < rawText.length(); i++) {
            char c = rawText.charAt(i);
            if (c == '\uFEFF' || c < 0x20) {
                cleaned.append(' ');
            } else {
                cleaned.append(c);
            }
        }
        return cleaned.toString().trim();
    }
}
