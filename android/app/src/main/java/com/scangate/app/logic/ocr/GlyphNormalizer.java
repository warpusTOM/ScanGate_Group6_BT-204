package com.scangate.app.logic.ocr;

/**
 * Turns a blob of ink into a fixed size grid, so two glyphs can be compared.
 *
 * A printed digit on a card might be 14 pixels tall on one frame and 40 on the
 * next, depending on how close the phone is. Comparing raw pixels is useless
 * until both shapes have been squashed into the same box. That is all this
 * class does, and it is the one piece of the recogniser that both the runtime
 * and the template generator must agree on, so it lives here and nowhere else.
 *
 * The grid is 12 wide by 18 tall. That is 216 cells, which is enough to tell a
 * 3 from an 8 on a printed card and small enough that matching a glyph against
 * every template costs nothing.
 *
 * The glyph keeps its shape. It is scaled by the same factor in both directions
 * and centred, so a tall thin 1 stays tall and thin instead of being stretched
 * out to fill the box. Squashing 1 to the same width as 0 is the classic way to
 * make a digit reader confuse the two.
 */
public final class GlyphNormalizer {

    public static final int GRID_WIDTH = 12;
    public static final int GRID_HEIGHT = 18;
    public static final int GRID_CELLS = GRID_WIDTH * GRID_HEIGHT;

    private GlyphNormalizer() {
    }

    /**
     * @param mask   one value per pixel, non zero meaning ink, laid out row by
     *               row. The caller has already cut this down to a single blob.
     * @param width  width of the mask
     * @param height height of the mask
     * @return GRID_CELLS coverage values, 0 for empty and 255 for solid ink
     */
    public static byte[] normalize(byte[] mask, int width, int height) {
        byte[] grid = new byte[GRID_CELLS];
        if (mask == null || width <= 0 || height <= 0) return grid;

        // 1. trim to the actual ink. Connected component boxes often carry a
        //    row or two of slack, and that slack would shift the glyph.
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                if (mask[rowStart + x] == 0) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right < left || bottom < top) return grid;   // nothing in it

        int inkWidth = right - left + 1;
        int inkHeight = bottom - top + 1;

        // 2. one scale factor for both axes, so the shape is preserved.
        float scale = Math.min(
                (float) GRID_WIDTH / inkWidth,
                (float) GRID_HEIGHT / inkHeight);

        int boxWidth = Math.max(1, Math.round(inkWidth * scale));
        int boxHeight = Math.max(1, Math.round(inkHeight * scale));
        int offsetX = (GRID_WIDTH - boxWidth) / 2;
        int offsetY = (GRID_HEIGHT - boxHeight) / 2;

        // 3. for every cell of the box, average the ink it covers. Averaging
        //    rather than picking one pixel is what makes this survive a blurry
        //    photo, where the edge of a stroke is grey rather than black.
        for (int cellY = 0; cellY < boxHeight; cellY++) {
            int sourceTop = top + (int) ((long) cellY * inkHeight / boxHeight);
            int sourceBottom = top + (int) ((long) (cellY + 1) * inkHeight / boxHeight);
            if (sourceBottom <= sourceTop) sourceBottom = sourceTop + 1;

            for (int cellX = 0; cellX < boxWidth; cellX++) {
                int sourceLeft = left + (int) ((long) cellX * inkWidth / boxWidth);
                int sourceRight = left + (int) ((long) (cellX + 1) * inkWidth / boxWidth);
                if (sourceRight <= sourceLeft) sourceRight = sourceLeft + 1;

                int ink = 0;
                int total = 0;
                for (int y = sourceTop; y < sourceBottom && y <= bottom; y++) {
                    int rowStart = y * width;
                    for (int x = sourceLeft; x < sourceRight && x <= right; x++) {
                        total++;
                        if (mask[rowStart + x] != 0) ink++;
                    }
                }
                if (total == 0) continue;

                int coverage = ink * 255 / total;
                grid[(offsetY + cellY) * GRID_WIDTH + (offsetX + cellX)] = (byte) coverage;
            }
        }
        return grid;
    }

    /**
     * Counts the enclosed background areas inside a glyph.
     *
     * This is the cheapest strong signal there is. An 8 has two holes, a 0, 6,
     * 9 and most printed 4s have one, and 1, 2, 3, 5 and 7 have none. Matching
     * on shape alone confuses 6 with 8 and 0 with 9 all day. Knowing how many
     * holes each one has removes most of that in one integer comparison.
     *
     * @param mask   the blob, non zero meaning ink
     * @param width  mask width
     * @param height mask height
     */
    public static int countHoles(byte[] mask, int width, int height) {
        if (mask == null || width <= 0 || height <= 0) return 0;

        boolean[] background = new boolean[width * height];
        for (int i = 0; i < background.length; i++) {
            background[i] = mask[i] == 0;
        }

        // Flood the background inwards from the border. Whatever background is
        // still unvisited afterwards is sealed inside the glyph, which is a
        // hole. One pass from the edges, then count what is left.
        int[] queue = new int[width * height];
        int head = 0;
        int tail = 0;

        for (int x = 0; x < width; x++) {
            tail = push(background, queue, tail, width, x, 0, width, height);
            tail = push(background, queue, tail, width, x, height - 1, width, height);
        }
        for (int y = 0; y < height; y++) {
            tail = push(background, queue, tail, width, 0, y, width, height);
            tail = push(background, queue, tail, width, width - 1, y, width, height);
        }

        while (head < tail) {
            int index = queue[head++];
            int x = index % width;
            int y = index / width;
            tail = push(background, queue, tail, width, x - 1, y, width, height);
            tail = push(background, queue, tail, width, x + 1, y, width, height);
            tail = push(background, queue, tail, width, x, y - 1, width, height);
            tail = push(background, queue, tail, width, x, y + 1, width, height);
        }

        // Now walk the leftovers. Each unvisited pocket is one hole, and the
        // flood fill stops it being counted twice.
        int holes = 0;
        for (int start = 0; start < background.length; start++) {
            if (!background[start]) continue;
            holes++;
            head = 0;
            tail = 0;
            background[start] = false;
            queue[tail++] = start;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                tail = push(background, queue, tail, width, x - 1, y, width, height);
                tail = push(background, queue, tail, width, x + 1, y, width, height);
                tail = push(background, queue, tail, width, x, y - 1, width, height);
                tail = push(background, queue, tail, width, x, y + 1, width, height);
            }
        }
        return holes;
    }

    private static int push(boolean[] background, int[] queue, int tail,
                            int width, int x, int y, int maxWidth, int maxHeight) {
        if (x < 0 || y < 0 || x >= maxWidth || y >= maxHeight) return tail;
        int index = y * width + x;
        if (!background[index]) return tail;
        background[index] = false;
        queue[tail] = index;
        return tail + 1;
    }
}
