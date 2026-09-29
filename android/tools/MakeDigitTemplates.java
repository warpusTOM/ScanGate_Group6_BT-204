import com.scangate.app.logic.ocr.GlyphMatcher;
import com.scangate.app.logic.ocr.GlyphNormalizer;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates DigitTemplates.java, the shape library the printed number reader
 * matches against.
 *
 * A digit recogniser needs something to compare against. The honest way to get
 * that is to render the ten digits in the fonts a school ID is likely to be
 * printed in, cut each one down to the same 12 by 18 grid the runtime uses, and
 * store the result. That is what this program does.
 *
 * It uses java.awt to render, which exists on a desktop but not on Android,
 * which is exactly why this is a build time step rather than something the app
 * does. The output is plain data, so the app never needs a font engine.
 *
 * Run it:
 *     java MakeDigitTemplates.java
 *
 * from the android folder. It rewrites
 * app/src/main/java/com/scangate/app/logic/ocr/DigitTemplates.java
 * and then reports how well the templates tell each other apart, which is the
 * one number worth checking before trusting the rest of the pipeline.
 */
public final class MakeDigitTemplates {

    /** The fonts a card is likely to be printed in. Sans, serif and mono, regular and bold. */
    private static final String[][] FONTS = {
            {"arial", "Arial"},
            {"arialbd", "Arial Bold"},
            {"verdana", "Verdana"},
            {"verdanab", "Verdana Bold"},
            {"tahoma", "Tahoma"},
            {"tahomabd", "Tahoma Bold"},
            {"times", "Times New Roman"},
            {"courbd", "Courier New Bold"},
            {"calibri", "Calibri"},
            {"consola", "Consolas"},
    };

    private static final Path FONT_DIR = Path.of("C:", "Windows", "Fonts");
    private static final Path OUTPUT = Path.of(
            "app", "src", "main", "java", "com", "scangate", "app",
            "logic", "ocr", "DigitTemplates.java");

    /** Rendered this big before being squashed down, so the shape is smooth. */
    private static final int RENDER_SIZE = 120;

    private static final List<byte[]> GRIDS = new ArrayList<>();
    private static final List<Integer> DIGITS = new ArrayList<>();
    private static final List<Integer> HOLES = new ArrayList<>();
    private static final List<String> SOURCES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (String[] entry : FONTS) {
            Font font = loadFont(entry[0]);
            if (font == null) {
                System.out.println("skip: " + entry[1] + " not found");
                continue;
            }
            for (int digit = 0; digit <= 9; digit++) {
                byte[] mask = renderMask(font, String.valueOf(digit));
                addTemplate(mask, digit, entry[1]);
            }
        }

        if (GRIDS.isEmpty()) {
            throw new IllegalStateException(
                    "No fonts loaded. Check " + FONT_DIR + " exists and has arial.ttf.");
        }

        writeSource();
        System.out.println();
        System.out.println("templates: " + GRIDS.size()
                + "  (" + FONTS.length + " fonts x 10 digits)");
        System.out.println("written  : " + OUTPUT.toAbsolutePath());
        System.out.println();
        reportSeparation();
    }

    // ------------------------------------------------------------------
    // rendering
    // ------------------------------------------------------------------

    private static Font loadFont(String baseName) {
        File file = FONT_DIR.resolve(baseName + ".ttf").toFile();
        if (!file.exists()) return null;
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT, file);
            return font.deriveFont(Font.PLAIN, RENDER_SIZE);
        } catch (Exception cannotLoad) {
            return null;
        }
    }

    /**
     * Draws one character and returns a binary ink mask, non zero meaning ink.
     *
     * The render is thresholded rather than kept as greyscale. The runtime
     * glyphs come out of a connected component pass, so they are already black
     * and white. Comparing a black and white glyph against a soft antialiased
     * template would put a bias on every edge pixel, so both sides get the same
     * treatment here.
     */
    private static byte[] renderMask(Font font, String character) {
        BufferedImage canvas = new BufferedImage(
                RENDER_SIZE * 2, RENDER_SIZE * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(font);

        java.awt.FontMetrics metrics = graphics.getFontMetrics();
        int width = metrics.stringWidth(character);
        int x = (canvas.getWidth() - width) / 2;
        int y = (canvas.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        graphics.drawString(character, x, y);
        graphics.dispose();

        byte[] mask = new byte[canvas.getWidth() * canvas.getHeight()];
        for (int py = 0; py < canvas.getHeight(); py++) {
            for (int px = 0; px < canvas.getWidth(); px++) {
                int rgb = canvas.getRGB(px, py);
                int grey = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                mask[py * canvas.getWidth() + px] = grey < 3 * 128 ? (byte) 1 : (byte) 0;
            }
        }
        return mask;
    }

    private static void addTemplate(byte[] mask, int digit, String fontName) {
        // The mask is the whole render canvas, so trim it to the ink first by
        // handing it to the normaliser, which does that itself.
        byte[] grid = GlyphNormalizer.normalize(mask, RENDER_SIZE * 2, RENDER_SIZE * 2);

        int ink = 0;
        for (byte value : grid) {
            if (value != 0) ink++;
        }
        if (ink < 20) return;   // empty or broken render, not worth keeping

        // Hole counting wants the tight mask, not the grid, so rebuild the
        // tight box from the render.
        int[] box = tightBox(mask, RENDER_SIZE * 2, RENDER_SIZE * 2);
        if (box == null) return;
        byte[] tight = crop(mask, RENDER_SIZE * 2, box);
        int holes = GlyphNormalizer.countHoles(tight, box[2] - box[0] + 1, box[3] - box[1] + 1);

        GRIDS.add(grid);
        DIGITS.add(digit);
        HOLES.add(holes);
        SOURCES.add(fontName);
    }

    private static int[] tightBox(byte[] mask, int width, int height) {
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x] == 0) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        return right < left ? null : new int[]{left, top, right, bottom};
    }

    private static byte[] crop(byte[] mask, int width, int[] box) {
        int cropWidth = box[2] - box[0] + 1;
        int cropHeight = box[3] - box[1] + 1;
        byte[] out = new byte[cropWidth * cropHeight];
        for (int y = 0; y < cropHeight; y++) {
            System.arraycopy(mask, (box[1] + y) * width + box[0],
                    out, y * cropWidth, cropWidth);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // output
    // ------------------------------------------------------------------

    private static void writeSource() throws Exception {
        try (PrintWriter out = new PrintWriter(OUTPUT.toFile(), StandardCharsets.UTF_8)) {
            out.println("package com.scangate.app.logic.ocr;");
            out.println();
            out.println("/**");
            out.println(" * The shape library the printed number reader matches against.");
            out.println(" *");
            out.println(" * Generated by tools/MakeDigitTemplates.java. Do not edit by hand.");
            out.println(" *");
            out.println(" * Each entry is one digit from one font, squashed into the same");
            out.println(" * " + GlyphNormalizer.GRID_WIDTH + "x" + GlyphNormalizer.GRID_HEIGHT
                    + " grid that GlyphNormalizer produces at runtime, so a");
            out.println(" * glyph off a camera frame and a template from here can be compared");
            out.println(" * cell by cell. The grids are stored as hex strings, two characters per");
            out.println(" * cell, because that keeps the file readable and about a third the size");
            out.println(" * of a byte array literal.");
            out.println(" */");
            out.println("public final class DigitTemplates {");
            out.println();
            out.println("    /** How many shapes are in the library. */");
            out.println("    public static final int COUNT = " + GRIDS.size() + ";");
            out.println();

            out.println("    /** Which digit each shape is, in the same order as GRIDS. */");
            out.println("    static final byte[] DIGIT = {");
            writeByteRow(out, DIGITS, 20);
            out.println("    };");
            out.println();

            out.println("    /** How many enclosed holes each shape has, in the same order. */");
            out.println("    static final byte[] HOLES = {");
            writeByteRow(out, HOLES, 20);
            out.println("    };");
            out.println();

            out.println("    /** Which font each shape came from. Only used by the tests. */");
            out.println("    static final String[] SOURCE = {");
            for (int i = 0; i < SOURCES.size(); i++) {
                out.print("        \"" + SOURCES.get(i) + "\"");
                out.println(i == SOURCES.size() - 1 ? "" : ",");
            }
            out.println("    };");
            out.println();

            out.println("    /** The shapes themselves, " + GlyphNormalizer.GRID_CELLS
                    + " cells each, as hex. */");
            out.println("    private static final String[] GRIDS = {");
            for (int i = 0; i < GRIDS.size(); i++) {
                out.print("        \"");
                for (byte value : GRIDS.get(i)) {
                    out.print(String.format("%02x", value & 0xFF));
                }
                out.print("\"");
                out.println(i == GRIDS.size() - 1 ? "" : ",");
            }
            out.println("    };");
            out.println();

            out.println("    /** The decoded shapes, " + GlyphNormalizer.GRID_CELLS
                    + " bytes per shape, laid out one after another. */");
            out.println("    static final byte[] SHAPES = decode();");
            out.println();
            out.println("    private static byte[] decode() {");
            out.println("        byte[] out = new byte[COUNT * " + GlyphNormalizer.GRID_CELLS + "];");
            out.println("        for (int i = 0; i < COUNT; i++) {");
            out.println("            String hex = GRIDS[i];");
            out.println("            for (int cell = 0; cell < " + GlyphNormalizer.GRID_CELLS
                    + "; cell++) {");
            out.println("                int high = Character.digit(hex.charAt(cell * 2), 16);");
            out.println("                int low = Character.digit(hex.charAt(cell * 2 + 1), 16);");
            out.println("                out[i * " + GlyphNormalizer.GRID_CELLS
                    + " + cell] = (byte) ((high << 4) | low);");
            out.println("            }");
            out.println("        }");
            out.println("        return out;");
            out.println("    }");
            out.println();
            out.println("    private DigitTemplates() {");
            out.println("    }");
            out.println("}");
        }
    }

    private static void writeByteRow(PrintWriter out, List<Integer> values, int perLine) {
        for (int i = 0; i < values.size(); i++) {
            if (i % perLine == 0) out.print("        ");
            out.print(values.get(i));
            if (i != values.size() - 1) out.print(", ");
            if (i % perLine == perLine - 1 || i == values.size() - 1) out.println();
        }
    }

    // ------------------------------------------------------------------
    // does the library actually tell digits apart?
    // ------------------------------------------------------------------

    /**
     * Classifies every template against the templates of the other fonts and
     * reports how many come back as the right digit.
     *
     * A font that is in the library will of course match itself, so the font
     * being tested is held out. What is left is the honest question: can a
     * shape the library has never seen be placed on the right digit?
     */
    private static void reportSeparation() {
        int correct = 0;
        int total = 0;
        StringBuilder failures = new StringBuilder();

        for (int test = 0; test < GRIDS.size(); test++) {
            byte[] probe = GRIDS.get(test);
            int expected = DIGITS.get(test);
            String source = SOURCES.get(test);

            int[] bestDistance = new int[10];
            java.util.Arrays.fill(bestDistance, Integer.MAX_VALUE);

            for (int candidate = 0; candidate < GRIDS.size(); candidate++) {
                if (SOURCES.get(candidate).equals(source)) continue;   // hold out its own font
                int digit = DIGITS.get(candidate);
                int distance = GlyphMatcher.distance(probe, GRIDS.get(candidate))
                        + GlyphMatcher.holePenalty(HOLES.get(test), HOLES.get(candidate));
                if (distance < bestDistance[digit]) bestDistance[digit] = distance;
            }

            int best = 0;
            for (int digit = 1; digit <= 9; digit++) {
                if (bestDistance[digit] < bestDistance[best]) best = digit;
            }

            total++;
            if (best == expected) {
                correct++;
            } else {
                failures.append(String.format("   %s '%d' read as '%d'%n", source, expected, best));
            }
        }

        double percent = total == 0 ? 0 : 100.0 * correct / total;
        System.out.printf("held-out separation: %d/%d  (%.1f%%)%n", correct, total, percent);
        if (failures.length() > 0) {
            System.out.print("confusions:");
            System.out.println();
            System.out.print(failures);
        }
    }

    private MakeDigitTemplates() {
    }
}
