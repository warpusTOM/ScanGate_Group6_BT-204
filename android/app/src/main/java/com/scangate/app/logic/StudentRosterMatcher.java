package com.scangate.app.logic;

import java.util.Collection;

/**
 * Corrects a read student number against the roster.
 *
 * This is the part that makes printed number reading actually work, and it is
 * worth being clear about why.
 *
 * A character reader that works off shapes gets a digit wrong now and then. An
 * 8 with a speck of dirt in the middle of it comes back as a 0. A worn 5 comes
 * back as a 6. On its own that is a failure, and the operator gets told the
 * student is not registered when the student is standing right there.
 *
 * But this app already knows something the reader does not: the exact set of
 * student numbers that exist. There are 262 of them in the roster. So instead
 * of asking "did I read this perfectly", the useful question is "which of the
 * numbers I already know is closest to what I read". A single wrong digit stops
 * mattering, because the wrong reading is still much closer to the right number
 * than to any other number in the roster.
 *
 * The safety rail matters as much as the correction. If the best match is not
 * clearly better than the second best, this refuses to guess and returns
 * nothing. Guessing between two students is worse than admitting the read
 * failed, because a wrong name on the screen at a gate is a real problem and a
 * "try again" is not.
 */
public final class StudentRosterMatcher {

    /** The closest known number, and how far off the read was. */
    public static final class Match {

        /** The student number as it appears in the roster. */
        public final String studentId;

        /** What the reader actually produced, before correction. */
        public final String readText;

        /** How many characters had to change. Zero means it read perfectly. */
        public final int corrections;

        public Match(String studentId, String readText, int corrections) {
            this.studentId = studentId;
            this.readText = readText;
            this.corrections = corrections;
        }

        public boolean isExact() {
            return corrections == 0;
        }

        @Override
        public String toString() {
            return isExact() ? studentId : studentId + " (read as " + readText + ")";
        }
    }

    /**
     * How many characters a read may be off by and still be corrected.
     *
     * One, not two. With 262 numbers of eight digits, a distance of one leaves
     * very little room for the wrong answer to win. A distance of two starts
     * letting genuinely different numbers collide, and the whole point of the
     * second-best check below is that this should almost never fire.
     */
    private static final int MAX_CORRECTIONS = 1;

    /** Numbers further apart in length than this are not worth comparing. */
    private static final int MAX_LENGTH_DIFFERENCE = 2;

    private StudentRosterMatcher() {
    }

    /**
     * Finds the roster entry a read number most likely came from.
     *
     * @param readText     what the reader produced, digits only
     * @param knownNumbers every student number in the roster
     * @return the match, or null when the read is too far from anything or too
     *         close to two different students to call
     */
    public static Match match(String readText, Collection<String> knownNumbers) {
        if (readText == null || readText.isEmpty() || knownNumbers == null) return null;

        String read = digitsOnly(readText);
        if (read.length() < 4) return null;

        // A clean read is worth taking at face value before doing any guessing.
        for (String known : knownNumbers) {
            if (read.equals(known)) return new Match(known, read, 0);
        }

        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int secondBestDistance = Integer.MAX_VALUE;

        for (String known : knownNumbers) {
            if (known == null || !isAllDigits(known)) continue;
            if (Math.abs(known.length() - read.length()) > MAX_LENGTH_DIFFERENCE) continue;

            int distance = editDistance(read, known, MAX_CORRECTIONS + 1);
            if (distance < bestDistance) {
                secondBestDistance = bestDistance;
                bestDistance = distance;
                best = known;
            } else if (distance < secondBestDistance) {
                secondBestDistance = distance;
            }
        }

        if (best == null) return null;
        if (bestDistance > MAX_CORRECTIONS) return null;

        // Refuse to pick between two students that fit the read equally well.
        if (secondBestDistance <= bestDistance) return null;

        return new Match(best, read, bestDistance);
    }

    /**
     * Levenshtein distance: the number of single character edits, meaning an
     * insert, a delete or a change, needed to turn one string into the other.
     *
     * Stops early once the distance passes limit, because there is no point
     * finishing a count that has already lost.
     */
    public static int editDistance(String left, String right, int limit) {
        if (left == null || right == null) return Integer.MAX_VALUE;
        if (left.equals(right)) return 0;

        int leftLength = left.length();
        int rightLength = right.length();
        if (Math.abs(leftLength - rightLength) > limit) return limit + 1;

        int[] previous = new int[rightLength + 1];
        int[] current = new int[rightLength + 1];

        for (int j = 0; j <= rightLength; j++) previous[j] = j;

        for (int i = 1; i <= leftLength; i++) {
            current[0] = i;
            int rowBest = current[0];

            for (int j = 1; j <= rightLength; j++) {
                int substitutionCost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                int value = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + substitutionCost);
                current[j] = value;
                if (value < rowBest) rowBest = value;
            }

            if (rowBest > limit) return limit + 1;

            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[rightLength];
    }

    private static String digitsOnly(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
        }
        return out.toString();
    }

    private static boolean isAllDigits(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
