package com.scangate.app.logic.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads a printed student number off a card.
 *
 * This is the whole recogniser. Camera frame in, candidate numbers out. It
 * never touches Android, so it can be run against a photograph or a synthetic
 * test card on a desktop and measured, which is the only reason it can be
 * tuned at all.
 *
 * The stages, in order:
 *
 *   1. threshold    decide which pixels are ink, compared against their own
 *                   neighbourhood so a shadow across the card does not matter
 *   2. find blobs   group the ink into separate lumps, one per printed character
 *   3. group lines  a student number is a row of lumps of the same height
 *                   sitting on the same line with small gaps between them
 *   4. classify     squash each lump into a 12 by 18 grid and find the closest
 *                   digit in the shape library
 *   5. extract      pull the runs of five or more digits out of each line, so a
 *                   label like "STUDENT NO" in front of the number is ignored
 *
 * What comes back is a short list, best first. It is deliberately a list and
 * not a single answer: a reader that is sure it is right is worse than useless
 * when it is wrong. The caller decides, usually by checking the answer against
 * the roster.
 */
public final class PrintedNumberReader {

    /** A number the reader thinks it saw. */
    public static final class Read {

        /** The digits, exactly as they were read. */
        public final String digits;

        /** 0 to 1. How much better the winning digit was than the runner up. */
        public final float confidence;

        /** How many characters were in the line this run came out of. */
        public final int glyphsInLine;

        public Read(String digits, float confidence, int glyphsInLine) {
            this.digits = digits;
            this.confidence = confidence;
            this.glyphsInLine = glyphsInLine;
        }

        @Override
        public String toString() {
            return digits + "  (" + Math.round(confidence * 100) + "% from "
                    + glyphsInLine + " glyphs)";
        }
    }

    // Tuned against the test cards in tools/tests. See tools/test_ocr_on_jvm.py
    // for what each one does to the score.
    private static final int MEDIUM_WINDOW_DIVISOR = 22;
    private static final int FINE_WINDOW_DIVISOR = 40;
    private static final int COARSE_WINDOW_DIVISOR = 9;
    private static final int MIN_WINDOW_RADIUS = 8;
    private static final int THRESHOLD_PERCENT = 88;

    private static final int MIN_GLYPHS_IN_LINE = 4;
    private static final int MIN_DIGITS_IN_RUN = 5;
    private static final int MAX_CANDIDATES = 6;

    /** Longer than this and a run is treated as several numbers stuck together. */
    private static final int OVERLONG_RUN = 11;

    /** Student numbers in the roster are eight digits, so those are the windows tried. */
    private static final int[] SLIDING_WINDOW_LENGTHS = {8, 7, 9};

    /** How much a sliced out window is trusted compared to a whole run. */
    private static final float WINDOW_CONFIDENCE_PENALTY = 0.75f;

    /**
     * A gap bigger than this many character heights starts a new line.
     *
     * Deliberately tight. A word space on a card is wider than the gap between
     * two digits of the same number, so this is what stops a label like
     * "STUDENT NO." from being glued onto the number in front of it. Normal
     * kerning between digits is a fraction of a character height, so nothing
     * inside a real number comes close to this.
     */
    private static final float MAX_GAP_IN_HEIGHTS = 0.9f;

    /** How far a lump may sit off the line's centre and still belong to it. */
    private static final float MAX_OFFSET_IN_HEIGHTS = 0.42f;

    /** How much taller or shorter a lump may be than the rest of its line. */
    private static final float MIN_HEIGHT_RATIO = 0.58f;
    private static final float MAX_HEIGHT_RATIO = 1.70f;

    private GrayImage image;

    /** Scratch for the turned frames, kept so a quarter turn allocates nothing. */
    private byte[] turnedPixels;
    private GrayImage turnedImage;

    /**
     * @param luminance one byte per pixel of brightness
     * @param width     frame width
     * @param height    frame height
     * @return candidate numbers, best first. Empty when nothing was readable.
     */
    public List<Read> read(byte[] luminance, int width, int height) {
        if (luminance == null || width <= 0 || height <= 0) {
            return new ArrayList<Read>();
        }
        return read(new GrayImage(luminance, width, height));
    }

    public List<Read> read(GrayImage frame) {
        this.image = frame;

        // Order matters, because most frames find nothing and this runs several
        // times a second while somebody lines the card up.
        //
        // Orientation comes first and the window size second. A camera buffer
        // is landscape with the card on its side, so the second view is the one
        // that usually hits, and it hits at the middle window size. Working
        // through orientations at one window size means the common case is two
        // passes instead of four.
        //
        // The wider window sizes are the fallback for unusually small or large
        // printing, and they only get tried once every orientation has failed
        // at the middle size.
        int shortest = Math.min(frame.width(), frame.height());
        int[] radii = {
                Math.max(MIN_WINDOW_RADIUS, shortest / MEDIUM_WINDOW_DIVISOR),
                Math.max(MIN_WINDOW_RADIUS, shortest / FINE_WINDOW_DIVISOR),
                Math.max(MIN_WINDOW_RADIUS, shortest / COARSE_WINDOW_DIVISOR),
        };

        int[] viewOrder = viewOrderFor(frame);

        for (int radiusIndex = 0; radiusIndex < radii.length; radiusIndex++) {
            if (radiusIndex > 0 && radii[radiusIndex] == radii[radiusIndex - 1]) continue;

            for (int step = 0; step < VIEWS; step++) {
                GrayImage frameInThisView = viewOf(frame, viewOrder[step]);
                List<Read> found = readWithWindow(frameInThisView, radii[radiusIndex]);
                if (!found.isEmpty()) return rank(found);
            }
        }
        return new ArrayList<Read>();
    }

    /** How many ways a frame is looked at: as it is, and turned each way. */
    private static final int VIEWS = 3;

    /**
     * Which of the three views to try first.
     *
     * A frame wider than it is tall is a camera buffer from a phone held
     * upright, which is how this app is used almost all of the time, and in
     * that frame the card is on its side. So the clockwise turn is the one that
     * usually hits, and putting it first saves a pass on nearly every frame.
     *
     * A frame already taller than it is wide is either a buffer from a device
     * whose sensor is mounted the other way round, or a picture handed in by
     * the test tools, and both of those are usually already the right way up.
     */
    private static int[] viewOrderFor(GrayImage frame) {
        if (frame.width() > frame.height()) {
            return new int[]{1, 0, 2};      // clockwise first
        }
        return new int[]{0, 1, 2};          // as it is first
    }

    private GrayImage viewOf(GrayImage frame, int view) {
        if (view == 0) return frame;
        return turn(frame, view == 1);
    }

    /** A quarter turned view of the frame, reusing the same buffers every time. */
    private GrayImage turn(GrayImage source, boolean clockwise) {
        int turnedWidth = source.height();
        int turnedHeight = source.width();
        int needed = turnedWidth * turnedHeight;

        if (turnedPixels == null || turnedPixels.length < needed) {
            turnedPixels = new byte[needed];
        }
        source.quarterTurnInto(turnedPixels, clockwise);

        if (turnedImage == null || turnedImage.width() != turnedWidth
                || turnedImage.height() != turnedHeight) {
            turnedImage = new GrayImage(turnedPixels, turnedWidth, turnedHeight);
        } else {
            turnedImage.wrap(turnedPixels);
        }
        return turnedImage;
    }

    private List<Read> readWithWindow(GrayImage frame, int windowRadius) {
        boolean[] dark = frame.threshold(windowRadius, THRESHOLD_PERCENT);
        List<InkBlob> blobs = frame.findBlobs(dark);

        List<Read> candidates = new ArrayList<Read>();
        List<List<InkBlob>> lines = groupIntoLines(blobs);

        for (int i = 0; i < lines.size(); i++) {
            readLine(lines.get(i), candidates);
        }
        return candidates;
    }

    /** Sorts best first and trims to the handful worth reporting. */
    private static List<Read> rank(List<Read> candidates) {
        Collections.sort(candidates, new Comparator<Read>() {
            @Override
            public int compare(Read left, Read right) {
                return Float.compare(score(right), score(left));
            }
        });
        if (candidates.size() > MAX_CANDIDATES) {
            return new ArrayList<Read>(candidates.subList(0, MAX_CANDIDATES));
        }
        return candidates;
    }

    /**
     * Ranks a read. Confidence first, then a nudge for lengths that look like a
     * student number, since eight digits is what the roster holds.
     */
    private static float score(Read read) {
        int length = read.digits.length();
        float lengthBonus;
        if (length >= 7 && length <= 10) {
            lengthBonus = 0.10f;
        } else if (length >= 5 && length <= 14) {
            lengthBonus = 0.0f;
        } else {
            lengthBonus = -0.25f;   // a 20 digit run is almost certainly a mis-merge
        }
        return read.confidence + lengthBonus;
    }

    // ------------------------------------------------------------------
    // grouping
    // ------------------------------------------------------------------

    /**
     * Groups lumps into rows.
     *
     * A printed student number is a row of marks that all stand about the same
     * height, sit about the same distance from the top, and are close together.
     * So lumps are sorted left to right and swept into rows, and a lump starts a
     * new row when it is the wrong height, sits too far off the line, or has too
     * big a gap in front of it.
     */
    private List<List<InkBlob>> groupIntoLines(List<InkBlob> blobs) {
        List<List<InkBlob>> lines = new ArrayList<List<InkBlob>>();
        if (blobs.isEmpty()) return lines;

        Collections.sort(blobs, new Comparator<InkBlob>() {
            @Override
            public int compare(InkBlob left, InkBlob right) {
                return left.left - right.left;
            }
        });

        List<InkBlob> current = new ArrayList<InkBlob>();
        current.add(blobs.get(0));

        for (int i = 1; i < blobs.size(); i++) {
            InkBlob blob = blobs.get(i);
            if (fitsTheLine(current, blob)) {
                current.add(blob);
            } else {
                if (current.size() >= MIN_GLYPHS_IN_LINE) lines.add(current);
                current = new ArrayList<InkBlob>();
                current.add(blob);
            }
        }
        if (current.size() >= MIN_GLYPHS_IN_LINE) lines.add(current);
        return lines;
    }

    private boolean fitsTheLine(List<InkBlob> line, InkBlob blob) {
        float medianHeight = medianHeight(line);
        if (medianHeight <= 0) return true;

        float ratio = blob.height / medianHeight;
        if (ratio < MIN_HEIGHT_RATIO || ratio > MAX_HEIGHT_RATIO) return false;

        int lineCenter = medianCenterY(line);
        if (Math.abs(blob.centerY() - lineCenter) > MAX_OFFSET_IN_HEIGHTS * medianHeight) {
            return false;
        }

        int previousRight = rightmost(line);
        int gap = blob.left - previousRight;
        return gap <= MAX_GAP_IN_HEIGHTS * medianHeight;
    }

    private static int rightmost(List<InkBlob> line) {
        int right = 0;
        for (int i = 0; i < line.size(); i++) {
            if (line.get(i).right() > right) right = line.get(i).right();
        }
        return right;
    }

    private static float medianHeight(List<InkBlob> line) {
        int[] heights = new int[line.size()];
        for (int i = 0; i < line.size(); i++) heights[i] = line.get(i).height;
        java.util.Arrays.sort(heights);
        return heights[heights.length / 2];
    }

    private static int medianCenterY(List<InkBlob> line) {
        int[] centres = new int[line.size()];
        for (int i = 0; i < line.size(); i++) centres[i] = line.get(i).centerY();
        java.util.Arrays.sort(centres);
        return centres[centres.length / 2];
    }

    // ------------------------------------------------------------------
    // reading one line
    // ------------------------------------------------------------------

    private void readLine(List<InkBlob> line, List<Read> candidates) {
        StringBuilder digits = new StringBuilder();
        float confidenceTotal = 0;

        for (int i = 0; i < line.size(); i++) {
            Digit digit = classify(line.get(i));
            digits.append((char) ('0' + digit.value));
            confidenceTotal += digit.confidence;
        }

        float lineConfidence = confidenceTotal / line.size();
        String text = digits.toString();

        // Pull the long digit runs out. A card that prints "STUDENT NO 20253152"
        // gives one line of lumps, and the letters in front of the number read
        // as whatever digits they most resemble. Taking the runs rather than the
        // whole line throws that noise away.
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) < '0' || text.charAt(index) > '9') {
                index++;
                continue;
            }
            int start = index;
            while (index < text.length()
                    && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                index++;
            }
            int length = index - start;
            if (length < MIN_DIGITS_IN_RUN) continue;

            String run = text.substring(start, index);
            candidates.add(new Read(run, lineConfidence, line.size()));

            // A label that reads entirely as digits leaves no gap to split on,
            // so "STUDENT NO 20263741" can arrive as one twenty character run.
            // Slicing plausible student number sized windows out of it gives
            // the roster matcher something to bite on.
            if (length > OVERLONG_RUN) {
                addSlidingWindows(run, lineConfidence, line.size(), candidates);
            }
        }
    }

    /** Windows of student number length, slid along an over long run. */
    private static void addSlidingWindows(String run, float confidence, int glyphs,
                                          List<Read> candidates) {
        int added = 0;
        for (int lengthIndex = 0; lengthIndex < SLIDING_WINDOW_LENGTHS.length; lengthIndex++) {
            int window = SLIDING_WINDOW_LENGTHS[lengthIndex];
            if (window >= run.length()) continue;

            for (int start = 0; start + window <= run.length(); start++) {
                if (added >= MAX_CANDIDATES * 3) return;
                candidates.add(new Read(run.substring(start, start + window),
                        confidence * WINDOW_CONFIDENCE_PENALTY, glyphs));
                added++;
            }
        }
    }

    /** One lump, read as a digit, with how sure the reader is. */
    private static final class Digit {
        final int value;
        final float confidence;

        Digit(int value, float confidence) {
            this.value = value;
            this.confidence = confidence;
        }
    }

    /**
     * Matches one lump against the shape library.
     *
     * Every template is scored, the best template for each of the ten digits is
     * kept, and the digit with the lowest score wins. Confidence is how far the
     * winner beat the best of the other nine. A glyph that could be a 6 or an 8
     * gets a low confidence, which is exactly the information the caller needs.
     */
    private Digit classify(InkBlob blob) {
        byte[] grid = GlyphNormalizer.normalize(blob.mask, blob.width, blob.height);
        int holes = GlyphNormalizer.countHoles(blob.mask, blob.width, blob.height);

        int[] bestPerDigit = new int[10];
        java.util.Arrays.fill(bestPerDigit, Integer.MAX_VALUE);

        for (int template = 0; template < DigitTemplates.COUNT; template++) {
            int digit = DigitTemplates.DIGIT[template];
            int score = GlyphMatcher.distance(grid, 0, DigitTemplates.SHAPES,
                    template * GlyphNormalizer.GRID_CELLS)
                    + GlyphMatcher.holePenalty(holes, DigitTemplates.HOLES[template]);
            if (score < bestPerDigit[digit]) bestPerDigit[digit] = score;
        }

        int winner = 0;
        int winnerScore = Integer.MAX_VALUE;
        int runnerUpScore = Integer.MAX_VALUE;

        for (int digit = 0; digit <= 9; digit++) {
            int score = bestPerDigit[digit];
            if (score < winnerScore) {
                runnerUpScore = winnerScore;
                winnerScore = score;
                winner = digit;
            } else if (score < runnerUpScore) {
                runnerUpScore = score;
            }
        }

        float confidence;
        if (winnerScore <= 0) {
            confidence = 1f;
        } else if (runnerUpScore == Integer.MAX_VALUE || runnerUpScore <= 0) {
            confidence = 0.5f;
        } else {
            confidence = (runnerUpScore - winnerScore) / (float) runnerUpScore;
        }
        if (confidence < 0) confidence = 0;
        if (confidence > 1) confidence = 1;

        return new Digit(winner, confidence);
    }
}
