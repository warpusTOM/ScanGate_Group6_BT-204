import com.scangate.app.data.RosterCsvReader;
import com.scangate.app.logic.StudentRosterMatcher;
import com.scangate.app.logic.ocr.PrintedNumberReader;
import com.scangate.app.model.Student;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Measures the printed number reader.
 *
 * A character reader cannot be checked by looking at it. It either reads the
 * numbers or it does not, and the only way to know is to feed it known numbers
 * and count. So this renders a synthetic student ID: a number drawn in a real
 * font on card coloured paper, then roughed up the way a phone camera would
 * rough it up. Blur, sensor noise, a brightness gradient from a lamp on one
 * side, a couple of degrees of tilt.
 *
 * Then it runs the real reader and reports three numbers:
 *
 *   top1     the first thing the reader said was exactly right
 *   any      the right number was somewhere in the list
 *   student  after correcting against the roster, the right student came back
 *
 * The third number is the one that matters, because that is what the app
 * actually does. The first two are there to show where the losses are.
 *
 * Usage:
 *     java OcrTestHarness <path to students.csv>
 */
public final class OcrTestHarness {

    /**
     * java.io.File rather than java.nio.file.Path on purpose. This file is
     * compiled with --release 8 to match the app, and Path.of did not exist
     * until Java 11.
     */
    private static final File FONT_DIR = new File("C:/Windows/Fonts");

    /** Font files a card might be printed in. */
    private static final String[] FONTS = {
            "arial", "arialbd", "verdana", "tahoma", "times", "cour", "calibri", "consola"
    };

    /** How many different numbers to draw from the roster for the test. */
    private static final int NUMBERS_TO_USE = 8;

    /**
     * Numbers to test with, taken from the roster itself.
     *
     * Using made up numbers would make the roster correction look worse than it
     * is, because a made up number cannot be corrected to anything. Real roster
     * entries are also what the app will actually meet, so they are what the
     * measurement should use. A stride picks them from across the whole file
     * rather than the first few, which are all in the same section.
     */
    private static String[] pickNumbers(List<String> roster) {
        List<String> numeric = new ArrayList<String>();
        for (int i = 0; i < roster.size(); i++) {
            if (isAllDigits(roster.get(i))) numeric.add(roster.get(i));
        }
        if (numeric.isEmpty()) return new String[]{"20253152"};

        int count = Math.min(NUMBERS_TO_USE, numeric.size());
        String[] picked = new String[count];
        int stride = Math.max(1, numeric.size() / count);
        for (int i = 0; i < count; i++) {
            picked[i] = numeric.get(Math.min(numeric.size() - 1, i * stride));
        }
        return picked;
    }

    private static boolean isAllDigits(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        }
        return true;
    }

    private static String[] numbers = {"20253152"};

    private static int cases;
    private static int topOneRight;
    private static int anyRight;
    private static int studentRight;
    private static int studentAttempted;
    private static final List<String> FAILURES = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        List<String> knownNumbers = loadRoster(args);
        numbers = pickNumbers(knownNumbers);

        System.out.println("printed number reader");
        System.out.println("roster: " + knownNumbers.size() + " numbers\n");
        System.out.printf("%-26s %-7s %-6s %-6s %-8s%n",
                "case", "size", "blur", "noise", "result");
        System.out.println("------------------------------------------------------------");

        // A spread that covers a careful scan and a sloppy one, without
        // exploding into every combination of everything.
        for (String fontName : FONTS) {
            for (int size : new int[]{30, 44, 62}) {
                runCase(knownNumbers, fontName, size, 0f, 0, false, false);
            }
        }
        for (String fontName : new String[]{"arial", "tahoma", "consola"}) {
            runCase(knownNumbers, fontName, 44, 1.2f, 0, false, false);
            runCase(knownNumbers, fontName, 44, 2.0f, 0, false, false);
            runCase(knownNumbers, fontName, 44, 0f, 10, false, false);
            runCase(knownNumbers, fontName, 44, 1.2f, 12, true, false);
            runCase(knownNumbers, fontName, 44, 1.2f, 12, true, true);
            runCase(knownNumbers, fontName, 30, 1.6f, 14, true, true);
        }

        // The number sitting next to a printed label, which is what a real
        // card looks like. The label must not end up in the answer.
        for (String fontName : new String[]{"arial", "tahoma"}) {
            runLabelledCase(knownNumbers, fontName, 44, 0f, 0);
            runLabelledCase(knownNumbers, fontName, 44, 1.2f, 10);
        }

        // The shape of frame the phone actually hands over.
        //
        // android.hardware.Camera gives a landscape buffer, because that is how
        // the sensor is mounted. A card held upright in front of a phone in
        // portrait therefore arrives SIDEWAYS in the buffer. Every case above
        // this line has the text upright, which is not what the app gets.
        System.out.println();
        System.out.println("camera frames (1280x720 buffer, card sideways)");
        System.out.println("------------------------------------------------------------");
        for (String fontName : new String[]{"arial", "tahoma", "verdana", "consola"}) {
            runCameraFrameCase(knownNumbers, fontName, 46, false, 0f, 0, false);
            runCameraFrameCase(knownNumbers, fontName, 46, true, 0f, 0, false);
            runCameraFrameCase(knownNumbers, fontName, 46, true, 1.0f, 8, true);
            runCameraFrameCase(knownNumbers, fontName, 30, true, 1.0f, 8, true);
        }

        // Student numbers on cards are often printed with wide letter spacing,
        // which is the thing that breaks line grouping if the gap allowance is
        // set too tight. Worth its own cases.
        System.out.println();
        System.out.println("camera frames, wide letter spacing");
        System.out.println("------------------------------------------------------------");
        for (String fontName : new String[]{"arial", "tahoma", "verdana"}) {
            runTrackedCase(knownNumbers, fontName, 46, true, 0f, 0);
            runTrackedCase(knownNumbers, fontName, 46, true, 1.0f, 8);
            runTrackedCase(knownNumbers, fontName, 34, true, 1.0f, 8);
        }

        System.out.println("------------------------------------------------------------");
        System.out.printf("cases            %d%n", cases);
        System.out.printf("exact reads      %d/%d  (%.1f%%)%n",
                topOneRight, cases, percent(topOneRight, cases));
        System.out.printf("number in list   %d/%d  (%.1f%%)%n",
                anyRight, cases, percent(anyRight, cases));
        System.out.printf("student resolved %d/%d  (%.1f%%)%n",
                studentRight, studentAttempted, percent(studentRight, studentAttempted));

        if (!FAILURES.isEmpty()) {
            System.out.println("\nmisses:");
            for (String failure : FAILURES) System.out.println("  " + failure);
        }

        // The bar. A clean, well lit card has to read exactly right or the
        // reader is not worth wiring into the app. The set deliberately
        // includes small print under a lamp with blur and noise, so this is not
        // a soft target: at 30px the digits are about twenty pixels tall and
        // one of the cases is not readable by anything.
        double exact = percent(topOneRight, cases);
        double resolved = percent(studentRight, studentAttempted);
        System.out.println();
        if (exact < 95.0 || resolved < 95.0) {
            System.out.printf("FAILED: exact %.1f%% and student %.1f%%, "
                    + "the bar is 95%% on both%n", exact, resolved);
            System.exit(1);
        }
        System.out.printf("PASSED: exact %.1f%%, student %.1f%%%n", exact, resolved);
    }

    private static double percent(int part, int whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }

    // ------------------------------------------------------------------
    // cases
    // ------------------------------------------------------------------

    private static void runCase(List<String> knownNumbers, String fontName, int size,
                                float blur, int noise, boolean gradient, boolean tilt)
            throws Exception {
        String truth = numbers[cases % numbers.length];
        BufferedImage card = renderCard(fontName, truth, size, blur, noise, gradient, tilt, null);
        evaluate(knownNumbers, label(fontName, size, blur, noise, gradient, tilt), truth, card);
    }

    private static void runLabelledCase(List<String> knownNumbers, String fontName, int size,
                                        float blur, int noise) throws Exception {
        String truth = numbers[cases % numbers.length];
        BufferedImage card = renderCard(fontName, truth, size, blur, noise, false, false,
                "STUDENT NO.");
        evaluate(knownNumbers, label(fontName, size, blur, noise, false, false) + " +label",
                truth, card);
    }

    private static String label(String fontName, int size, float blur, int noise,
                                boolean gradient, boolean tilt) {
        StringBuilder out = new StringBuilder(fontName);
        out.append(" ").append(size).append("px");
        if (blur > 0) out.append(" blur").append(blur);
        if (noise > 0) out.append(" noise").append(noise);
        if (gradient) out.append(" lamp");
        if (tilt) out.append(" tilt");
        return out.toString();
    }

    /**
     * A case drawn the way a camera frame really arrives.
     *
     * The number is drawn into a portrait picture first, which is what a person
     * holding the phone would see, and then turned a quarter turn to produce
     * the landscape buffer the sensor actually hands over. With sideways false
     * the picture is left as the person sees it, which makes a control: if the
     * upright version reads and the sideways one does not, the reader only
     * works in one orientation and the app will never work in the hand.
     */
    private static void runCameraFrameCase(List<String> knownNumbers, String fontName,
                                           int size, boolean sideways, float blur,
                                           int noise, boolean gradient) throws Exception {
        String truth = numbers[cases % numbers.length];
        BufferedImage frame = renderCameraFrame(fontName, truth, size, sideways,
                blur, noise, gradient, 0f);

        String name = fontName + " " + size + "px " + (sideways ? "sideways" : "upright");
        if (blur > 0) name += " blur" + blur;
        if (noise > 0) name += " noise" + noise;
        if (gradient) name += " lamp";
        evaluate(knownNumbers, name, truth, frame);
    }

    /** The same, with the digits spread out the way a card often prints them. */
    private static void runTrackedCase(List<String> knownNumbers, String fontName,
                                       int size, boolean sideways, float blur, int noise)
            throws Exception {
        String truth = numbers[cases % numbers.length];
        BufferedImage frame = renderCameraFrame(fontName, truth, size, sideways,
                blur, noise, false, 0.35f);

        String name = fontName + " " + size + "px tracked";
        if (blur > 0) name += " blur" + blur;
        if (noise > 0) name += " noise" + noise;
        evaluate(knownNumbers, name, truth, frame);
    }

    private static BufferedImage renderCameraFrame(String fontName, String number, int size,
                                                   boolean sideways, float blur, int noise,
                                                   boolean gradient, float tracking)
            throws Exception {
        int portraitWidth = 720;
        int portraitHeight = 1280;

        BufferedImage portrait = new BufferedImage(
                portraitWidth, portraitHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = portrait.createGraphics();
        graphics.setColor(new Color(232, 231, 226));
        graphics.fillRect(0, 0, portraitWidth, portraitHeight);

        Font font = loadFont(fontName, size);
        graphics.setFont(font);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        java.awt.FontMetrics metrics = graphics.getFontMetrics();
        int baseline = portraitHeight / 2 + metrics.getAscent() / 2;
        graphics.setColor(new Color(28, 30, 34));

        if (tracking <= 0) {
            int textWidth = metrics.stringWidth(number);
            graphics.drawString(number, (portraitWidth - textWidth) / 2, baseline);
        } else {
            int extra = Math.round(size * tracking);
            int totalWidth = 0;
            for (int i = 0; i < number.length(); i++) {
                totalWidth += metrics.charWidth(number.charAt(i)) + extra;
            }
            totalWidth -= extra;

            int x = (portraitWidth - totalWidth) / 2;
            for (int i = 0; i < number.length(); i++) {
                char c = number.charAt(i);
                graphics.drawString(String.valueOf(c), x, baseline);
                x += metrics.charWidth(c) + extra;
            }
        }
        graphics.dispose();

        BufferedImage frame = sideways ? rotateQuarterTurn(portrait) : portrait;
        if (gradient) frame = applyLamp(frame);
        if (blur > 0) frame = boxBlur(frame, Math.round(blur));
        if (noise > 0) frame = addNoise(frame, noise, 7717 + cases);
        return frame;
    }

    /**
     * Turns a portrait picture into the landscape buffer the camera hands over.
     *
     * The sensor is mounted a quarter turn from the way the phone is held, so
     * the buffer is always landscape and the scene inside it is always on its
     * side. This is the inverse of the rotation the app has to apply before it
     * can read anything.
     */
    private static BufferedImage rotateQuarterTurn(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(y, width - 1 - x, source.getRGB(x, y));
            }
        }
        return out;
    }

    private static void evaluate(List<String> knownNumbers, String caseName,
                                 String truth, BufferedImage card) {
        cases++;

        byte[] luminance = toLuminance(card);
        List<PrintedNumberReader.Read> reads =
                new PrintedNumberReader().read(luminance, card.getWidth(), card.getHeight());

        String top = reads.isEmpty() ? "" : reads.get(0).digits;
        boolean exact = truth.equals(top);
        boolean anywhere = false;
        for (int i = 0; i < reads.size(); i++) {
            if (truth.equals(reads.get(i).digits)) anywhere = true;
        }

        // Now the thing the app really does: try each candidate against the
        // roster and take the first one that lands on a real student.
        String resolved = null;
        for (int i = 0; i < reads.size() && resolved == null; i++) {
            StudentRosterMatcher.Match match =
                    StudentRosterMatcher.match(reads.get(i).digits, knownNumbers);
            if (match != null) resolved = match.studentId;
        }

        boolean studentOk = truth.equals(resolved);
        if (knownNumbers.contains(truth)) studentAttempted++;

        if (exact) topOneRight++;
        if (anywhere) anyRight++;
        if (studentOk) studentRight++;

        if (!exact) {
            FAILURES.add(String.format("%-40s wanted %s got %s%s",
                    caseName, truth,
                    reads.isEmpty() ? "(nothing)" : top,
                    resolved == null ? "" : " -> " + resolved));
        }

        System.out.printf("%-26s %-7s %-6s %-6s %s%n",
                caseName.length() > 26 ? caseName.substring(0, 26) : caseName,
                "", "", "",
                exact ? "OK" : (anywhere ? "in list" : "MISS")
                        + (studentOk ? " / student" : ""));
    }

    // ------------------------------------------------------------------
    // drawing a fake card
    // ------------------------------------------------------------------

    private static BufferedImage renderCard(String fontName, String number, int size,
                                            float blur, int noise, boolean gradient,
                                            boolean tilt, String label) throws Exception {
        int width = 960;
        int height = 300;

        BufferedImage card = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = card.createGraphics();
        graphics.setColor(new Color(232, 231, 226));    // card stock
        graphics.fillRect(0, 0, width, height);

        Font font = loadFont(fontName, size);
        graphics.setFont(font);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        String text = label == null ? number : label + "  " + number;
        java.awt.FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(text);

        if (tilt) {
            double radians = Math.toRadians(2.0);
            AffineTransform transform = new AffineTransform();
            transform.rotate(radians, width / 2.0, height / 2.0);
            graphics.setTransform(transform);
        }

        graphics.setColor(new Color(28, 30, 34));
        graphics.drawString(text,
                (width - textWidth) / 2,
                height / 2 + metrics.getAscent() / 2);
        graphics.dispose();

        BufferedImage result = card;
        if (gradient) result = applyLamp(result);
        if (blur > 0) result = boxBlur(result, Math.round(blur));
        if (noise > 0) result = addNoise(result, noise, 12345 + cases);
        return result;
    }

    private static Font loadFont(String baseName, int size) {
        File file = new File(FONT_DIR, baseName + ".ttf");
        if (file.exists()) {
            try {
                return Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(Font.PLAIN, size);
            } catch (Exception ignore) {
                // fall through to the built in font
            }
        }
        return new Font("SansSerif", Font.PLAIN, size);
    }

    /** A lamp on the left, so one side of the card is much brighter. */
    private static BufferedImage applyLamp(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double factor = 1.30 - 0.55 * x / (double) width;
                out.setRGB(x, y, scale(source.getRGB(x, y), factor));
            }
        }
        return out;
    }

    private static int scale(int rgb, double factor) {
        int r = clamp((int) (((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int) (((rgb >> 8) & 0xFF) * factor));
        int b = clamp((int) ((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static BufferedImage boxBlur(BufferedImage source, int radius) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                int count = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int sy = y + dy;
                    if (sy < 0 || sy >= height) continue;
                    for (int dx = -radius; dx <= radius; dx++) {
                        int sx = x + dx;
                        if (sx < 0 || sx >= width) continue;
                        int rgb = source.getRGB(sx, sy);
                        r += (rgb >> 16) & 0xFF;
                        g += (rgb >> 8) & 0xFF;
                        b += rgb & 0xFF;
                        count++;
                    }
                }
                out.setRGB(x, y, ((r / count) << 16) | ((g / count) << 8) | (b / count));
            }
        }
        return out;
    }

    /** Sensor noise. A phone in a dim hallway has plenty of it. */
    private static BufferedImage addNoise(BufferedImage source, int sigma, long seed) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                int r = clamp(((rgb >> 16) & 0xFF) + random.nextInt(2 * sigma + 1) - sigma);
                int g = clamp(((rgb >> 8) & 0xFF) + random.nextInt(2 * sigma + 1) - sigma);
                int b = clamp((rgb & 0xFF) + random.nextInt(2 * sigma + 1) - sigma);
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static byte[] toLuminance(BufferedImage image) {
        byte[] out = new byte[image.getWidth() * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                out[y * image.getWidth() + x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
            }
        }
        return out;
    }

    private static List<String> loadRoster(String[] args) throws Exception {
        List<String> numbers = new ArrayList<String>();
        if (args.length == 0) return numbers;

        File file = new File(args[0]);
        if (!file.exists()) return numbers;

        InputStream stream = new FileInputStream(file);
        List<Student> students;
        try {
            students = RosterCsvReader.read(stream);
        } finally {
            stream.close();
        }
        for (int i = 0; i < students.size(); i++) {
            numbers.add(students.get(i).studentId);
        }
        return numbers;
    }

    private OcrTestHarness() {
    }
}
