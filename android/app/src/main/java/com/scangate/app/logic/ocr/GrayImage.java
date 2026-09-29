package com.scangate.app.logic.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * A grayscale picture, plus the two operations the reader needs from one.
 *
 * The input is the brightness plane of a camera frame: one byte per pixel,
 * darker meaning darker. Everything the printed number reader does happens on
 * this class, and nothing here touches Android, so the whole thing can be run
 * and measured on a desktop against test pictures.
 *
 * Two jobs:
 *
 *   threshold()  decide which pixels are ink. This has to be adaptive, meaning
 *                it compares every pixel against its own neighbourhood rather
 *                than against one global cutoff. A card held under a lamp has a
 *                bright half and a shadowed half, and a single cutoff either
 *                turns the bright half blank or fills the dark half with
 *                speckle. Comparing locally is the difference between a reader
 *                that works on a desk and one that only works in a studio.
 *
 *   findBlobs()  group the ink into separate lumps. Digits on a card do not
 *                touch each other, so each digit comes out as one lump, and the
 *                shape of each lump can then be read on its own.
 *
 * The scratch buffers are kept as fields and reused. A 1280x720 frame is about
 * a million pixels, and allocating four million-element arrays on every frame
 * would keep the garbage collector busy enough to make the preview stutter.
 */
public final class GrayImage {

    private byte[] pixels;
    private final int width;
    private final int height;

    // Scratch space, grown on demand and then kept across frames.
    private long[] integral;
    private boolean[] darkMask;
    private boolean[] visited;
    private int[] stack;
    private int[] componentPixels;

    public GrayImage(byte[] luminance, int width, int height) {
        if (luminance == null) throw new IllegalArgumentException("luminance is null");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("empty image");
        if (luminance.length < width * height) {
            throw new IllegalArgumentException("luminance is shorter than " + width + "x" + height);
        }
        this.pixels = luminance;
        this.width = width;
        this.height = height;
    }

    /**
     * Points this image at a different buffer, keeping the scratch space.
     *
     * The camera hands over a new frame about three times a second while the
     * reader is running. Building a fresh GrayImage each time would throw away
     * four arrays of a million elements and have the garbage collector working
     * hard enough to stutter the preview, so the caller keeps one of these and
     * points it at each new frame instead.
     */
    public void wrap(byte[] luminance) {
        if (luminance == null) throw new IllegalArgumentException("luminance is null");
        if (luminance.length < width * height) {
            throw new IllegalArgumentException("luminance is shorter than " + width + "x" + height);
        }
        this.pixels = luminance;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte[] pixels() {
        return pixels;
    }

    /**
     * Writes a quarter turn of this image into a destination buffer.
     *
     * This exists because of how phone cameras are built. The sensor is mounted
     * a quarter turn from the way the phone is held, so android.hardware.Camera
     * hands over a landscape buffer no matter which way up the phone is. A card
     * held upright in front of a phone in portrait therefore arrives sideways,
     * and a reader that only looks for rows of characters finds nothing at all.
     *
     * A plain transpose would be cheaper but it mirrors the characters as well
     * as turning them, and a mirrored 2 is not a 2. A quarter turn keeps them
     * the right way round.
     *
     * The destination is a caller supplied buffer rather than a new array
     * because this runs on frames that found nothing, which is most of them
     * while somebody is still lining the card up.
     *
     * @param destination at least width * height bytes
     * @param clockwise   true for a quarter turn clockwise
     */
    public void quarterTurnInto(byte[] destination, boolean clockwise) {
        if (destination == null || destination.length < width * height) {
            throw new IllegalArgumentException("destination buffer is too small");
        }
        int turnedWidth = height;
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                int turnedX;
                int turnedY;
                if (clockwise) {
                    turnedX = height - 1 - y;
                    turnedY = x;
                } else {
                    turnedX = y;
                    turnedY = width - 1 - x;
                }
                destination[turnedY * turnedWidth + turnedX] = pixels[rowStart + x];
            }
        }
    }

    public int pixelAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0;
        return pixels[y * width + x] & 0xFF;
    }

    /**
     * Marks every pixel that is clearly darker than the area around it.
     *
     * @param windowRadius how big a neighbourhood to compare against, roughly
     *                     the size of a couple of digits. Too small and the
     *                     inside of a thick stroke reads as background, too
     *                     large and a shadow across the card stops being
     *                     corrected.
     * @param percent      how far below the local average a pixel has to be.
     *                     86 means 14 percent darker. Higher catches fainter
     *                     print but starts picking up card texture.
     */
    public boolean[] threshold(int windowRadius, int percent) {
        int cells = width * height;
        if (integral == null || integral.length < (width + 1) * (height + 1)) {
            integral = new long[(width + 1) * (height + 1)];
        }
        if (darkMask == null || darkMask.length < cells) {
            darkMask = new boolean[cells];
        }

        buildIntegral();
        int stride = width + 1;

        for (int y = 0; y < height; y++) {
            int top = Math.max(0, y - windowRadius);
            int bottom = Math.min(height - 1, y + windowRadius);

            for (int x = 0; x < width; x++) {
                int left = Math.max(0, x - windowRadius);
                int right = Math.min(width - 1, x + windowRadius);

                long sum = integral[(bottom + 1) * stride + (right + 1)]
                        - integral[top * stride + (right + 1)]
                        - integral[(bottom + 1) * stride + left]
                        + integral[top * stride + left];
                long area = (long) (right - left + 1) * (bottom - top + 1);
                long mean = sum / area;

                int value = pixels[y * width + x] & 0xFF;
                darkMask[y * width + x] = value * 100L < mean * percent;
            }
        }
        return darkMask;
    }

    /** Summed area table. Any box sum then costs four reads instead of a loop. */
    private void buildIntegral() {
        int stride = width + 1;
        for (int y = 0; y < height; y++) {
            long rowSum = 0;
            int row = (y + 1) * stride;
            int previousRow = y * stride;
            for (int x = 0; x < width; x++) {
                rowSum += pixels[y * width + x] & 0xFF;
                integral[row + x + 1] = integral[previousRow + x + 1] + rowSum;
            }
        }
    }

    /**
     * Finds every separate lump of ink and returns the ones that could be a
     * printed character.
     *
     * Eight-connected on purpose. A digit printed on a card and photographed at
     * an angle often has a diagonal stroke joined only corner to corner, and
     * four-connectivity would split it into two lumps.
     *
     * @param dark a mask from threshold()
     */
    public List<InkBlob> findBlobs(boolean[] dark) {
        List<InkBlob> blobs = new ArrayList<InkBlob>();
        int cells = width * height;

        if (visited == null || visited.length < cells) {
            visited = new boolean[cells];
            stack = new int[cells];
            componentPixels = new int[cells];
        }
        java.util.Arrays.fill(visited, 0, cells, false);

        for (int start = 0; start < cells; start++) {
            if (!dark[start] || visited[start]) continue;

            int stackSize = 0;
            stack[stackSize++] = start;
            visited[start] = true;

            int minX = width;
            int minY = height;
            int maxX = -1;
            int maxY = -1;
            int count = 0;

            while (stackSize > 0) {
                int index = stack[--stackSize];
                int x = index % width;
                int y = index / width;
                componentPixels[count++] = index;

                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) continue;
                    int rowStart = ny * width;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) continue;
                        int neighbour = rowStart + nx;
                        if (!dark[neighbour] || visited[neighbour]) continue;
                        visited[neighbour] = true;
                        stack[stackSize++] = neighbour;
                    }
                }
            }

            int blobWidth = maxX - minX + 1;
            int blobHeight = maxY - minY + 1;
            byte[] mask = new byte[blobWidth * blobHeight];
            for (int i = 0; i < count; i++) {
                int index = componentPixels[i];
                int x = index % width;
                int y = index / width;
                mask[(y - minY) * blobWidth + (x - minX)] = 1;
            }

            InkBlob blob = new InkBlob(minX, minY, blobWidth, blobHeight, mask, count);
            if (blob.looksLikeADigit()) blobs.add(blob);
        }
        return blobs;
    }
}
