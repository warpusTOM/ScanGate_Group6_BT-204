package com.scangate.app.logic.ocr;

/**
 * Compares two normalised glyphs and says how different they are.
 *
 * The obvious way to compare two 12 by 18 grids is to subtract them cell by
 * cell and add up the squares. That mostly works, and it is what the first
 * version of this did, but it has one weakness that shows up immediately: it is
 * very sensitive to a one cell shift. A glyph that is otherwise a perfect match
 * but sits one cell to the left scores badly, and a wrong digit that happens to
 * line up scores well. Times New Roman's 5 was being read as a 3 for exactly
 * this reason.
 *
 * So the comparison tries the nine small shifts and keeps the best score. Two
 * shapes count as similar if they look alike when one of them is nudged by a
 * cell in any direction, which is what a person does when they read a blurry
 * number. It costs nine times as much as the plain version and it is still
 * nothing: 216 cells times 9 shifts times 100 templates is under 200,000
 * subtractions, which a phone does in well under a millisecond.
 */
public final class GlyphMatcher {

    /** How far a glyph is allowed to slide while still counting as the same shape. */
    private static final int MAX_SHIFT = 1;

    private GlyphMatcher() {
    }

    /**
     * How different two grids are. Lower is more alike. Zero means identical.
     *
     * The value is a sum of squared differences over 216 cells, so it runs from
     * 0 up to about 216 * 255 * 255. It is only ever compared against other
     * values from this same method, never against a fixed threshold.
     */
    public static int distance(byte[] left, byte[] right) {
        return distance(left, 0, right, 0);
    }

    /**
     * The same comparison, but reading each grid out of a larger array at an
     * offset.
     *
     * The shape library stores all 100 templates end to end in one flat array,
     * because a hundred small arrays is a hundred objects for the garbage
     * collector to chase. This overload is what lets that flat array be
     * compared directly instead of being sliced into copies first. At eight
     * glyphs a frame times a hundred templates, copying would be 170 KB of
     * rubbish per frame for no reason.
     */
    public static int distance(byte[] left, int leftOffset,
                               byte[] right, int rightOffset) {
        if (left == null || right == null) return Integer.MAX_VALUE;
        if (left.length < leftOffset + GlyphNormalizer.GRID_CELLS) return Integer.MAX_VALUE;
        if (right.length < rightOffset + GlyphNormalizer.GRID_CELLS) return Integer.MAX_VALUE;

        int best = Integer.MAX_VALUE;
        for (int shiftY = -MAX_SHIFT; shiftY <= MAX_SHIFT; shiftY++) {
            for (int shiftX = -MAX_SHIFT; shiftX <= MAX_SHIFT; shiftX++) {
                int sum = 0;
                for (int y = 0; y < GlyphNormalizer.GRID_HEIGHT; y++) {
                    int rightY = y + shiftY;
                    boolean rowInRange = rightY >= 0 && rightY < GlyphNormalizer.GRID_HEIGHT;
                    int leftRow = leftOffset + y * GlyphNormalizer.GRID_WIDTH;
                    int rightRow = rightOffset + rightY * GlyphNormalizer.GRID_WIDTH;

                    for (int x = 0; x < GlyphNormalizer.GRID_WIDTH; x++) {
                        int a = left[leftRow + x] & 0xFF;
                        int b = 0;
                        if (rowInRange) {
                            int rightX = x + shiftX;
                            if (rightX >= 0 && rightX < GlyphNormalizer.GRID_WIDTH) {
                                b = right[rightRow + rightX] & 0xFF;
                            }
                        }
                        int difference = a - b;
                        sum += difference * difference;
                    }
                }
                if (sum < best) best = sum;
            }
        }
        return best;
    }

    /**
     * The cost of two glyphs having different hole counts.
     *
     * Shape alone cannot separate 0 from 8, or 6 from 8, or 9 from 3 in some
     * fonts, because the difference is a small loop that barely moves the
     * squared difference. The hole count makes that difference enormous instead
     * of tiny, which is why it is added on top of the shape distance rather
     * than being one more thing the shape has to express.
     */
    public static int holePenalty(int leftHoles, int rightHoles) {
        int difference = leftHoles - rightHoles;
        return difference * difference * HOLE_PENALTY_WEIGHT;
    }

    /**
     * Tuned so that one hole of difference costs more than any plausible shape
     * difference between two renderings of the same digit, but not so much that
     * a misread hole count on a torn card can never be overcome.
     */
    private static final int HOLE_PENALTY_WEIGHT = 40_000;
}
