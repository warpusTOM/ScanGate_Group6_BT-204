package com.scangate.app.logic.ocr;

/**
 * One lump of ink found in a picture.
 *
 * A student number printed on a card is a row of separate marks: eight digits,
 * each one its own lump. Finding the lumps is the first half of reading them,
 * and this is the second half. It holds the box the lump sits in, plus a copy
 * of just that lump's pixels so the rest of the picture can be forgotten.
 *
 * The copy matters. Grouping digits into a line and classifying each one needs
 * the lump on its own, not a window cut out of the frame, because a window
 * around a digit also contains the edges of its neighbours and those edges
 * wreck the shape.
 */
public final class InkBlob {

    public final int left;
    public final int top;
    public final int width;
    public final int height;

    /** One value per pixel of the box, non zero meaning ink. */
    public final byte[] mask;

    /** How many pixels in the box are ink. */
    public final int inkPixels;

    public InkBlob(int left, int top, int width, int height, byte[] mask, int inkPixels) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.mask = mask;
        this.inkPixels = inkPixels;
    }

    public int right() {
        return left + width - 1;
    }

    public int bottom() {
        return top + height - 1;
    }

    public int centerX() {
        return left + width / 2;
    }

    public int centerY() {
        return top + height / 2;
    }

    /** Width divided by height. A 1 is a long way below 1, a 0 is around 0.6. */
    public float aspect() {
        return height == 0 ? 0 : (float) width / height;
    }

    /** How much of the box is ink. A hollow 0 is low, a solid block is high. */
    public float fill() {
        int area = width * height;
        return area == 0 ? 0 : (float) inkPixels / area;
    }

    /**
     * Is this shaped like a printed digit?
     *
     * The limits are wide on purpose. They are here to throw away specks of
     * dust and big smears, not to decide what a digit is. The shape library
     * does that later. Being strict here would throw away real digits that are
     * partly worn, which is worse than passing a few extra lumps through.
     */
    public boolean looksLikeADigit() {
        if (inkPixels < 14) return false;
        if (height < 11 || height > 150) return false;
        if (width < 3 || width > 120) return false;
        if (aspect() < 0.08f || aspect() > 1.1f) return false;
        return fill() >= 0.13f && fill() <= 0.92f;
    }

    @Override
    public String toString() {
        return "blob[" + left + "," + top + " " + width + "x" + height
                + " ink=" + inkPixels + "]";
    }
}
