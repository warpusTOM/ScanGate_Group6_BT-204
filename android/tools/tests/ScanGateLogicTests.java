import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.scangate.app.data.RosterCsvReader;
import com.scangate.app.logic.BarcodeDecoder;
import com.scangate.app.logic.ClassClock;
import com.scangate.app.logic.StudentNumberParser;
import com.scangate.app.model.ClassTimeSettings;
import com.scangate.app.model.Student;

import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * Test layer 3 - the logic, run on a normal computer.
 *
 * Everything under com.scangate.app.logic, the model classes and the CSV
 * reader are plain Java with no Android in them, so they can be compiled
 * against the JDK and run here. That covers the part of the app where the
 * real mistakes live: time maths, the student number parser, the CSV import,
 * and the barcode decoder itself.
 *
 * The barcode test is the interesting one. It builds a real Code 128 and a
 * real QR code in memory using the ZXing encoder, paints them into the same
 * kind of brightness plane a camera frame produces, and then asks the app's
 * own BarcodeDecoder to read them back. If that passes, the decode path in
 * the apk is working - it is the same code, just fed a picture instead of a
 * camera.
 *
 * No JUnit, so there is nothing to download.
 */
public final class ScanGateLogicTests {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("ScanGate logic tests");

        testClassTimeSettings();
        testClockFormatting();
        testTimeClassification();
        testStudentNumberParser();
        testRosterCsvReader();
        testBarcodeDecoder();
        testRosterFile(args);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // class time settings
    // ------------------------------------------------------------------

    private static void testClassTimeSettings() {
        System.out.println("\nclass time settings");

        checkEquals("08:00 parses", "8:0",
                join(ClassTimeSettings.parseClock("08:00")));
        checkEquals("8:5 parses", "8:5",
                join(ClassTimeSettings.parseClock("8:5")));
        checkEquals("13:30 parses", "13:30",
                join(ClassTimeSettings.parseClock("13:30")));
        checkEquals("08:05:00 parses", "8:5",
                join(ClassTimeSettings.parseClock("08:05:00")));
        checkEquals("24:00 rejected", null,
                ClassTimeSettings.parseClock("24:00"));
        checkEquals("08:60 rejected", null,
                ClassTimeSettings.parseClock("08:60"));
        checkEquals("0800 rejected", null,
                ClassTimeSettings.parseClock("0800"));
        checkEquals("words rejected", null,
                ClassTimeSettings.parseClock("half past eight"));
        checkEquals("null rejected", null,
                ClassTimeSettings.parseClock(null));

        checkEquals("default window text", "08:00",
                ClassTimeSettings.defaults().startTimeText());
        checkEquals("afternoon window text", "13:30",
                new ClassTimeSettings(13, 30, 15, 10).startTimeText());
        checkEquals("hour is clamped", 23,
                new ClassTimeSettings(99, 0, 15, 10).startHour);
        checkEquals("negative window is clamped", 0,
                new ClassTimeSettings(8, 0, -5, -5).earlyBeforeMinutes);
    }

    // ------------------------------------------------------------------
    // 12-hour formatting
    // ------------------------------------------------------------------

    private static void testClockFormatting() {
        System.out.println("\n12-hour clock");

        checkEquals("midnight", "12:10:00 AM", ClassClock.to12Hour("00:10:00"));
        checkEquals("morning", "8:27:28 AM", ClassClock.to12Hour("08:27:28"));
        checkEquals("ten stays ten", "10:00:00 AM", ClassClock.to12Hour("10:00:00"));
        checkEquals("eleven stays eleven", "11:59:59 AM", ClassClock.to12Hour("11:59:59"));
        checkEquals("noon", "12:05:00 PM", ClassClock.to12Hour("12:05:00"));
        checkEquals("afternoon", "1:00:00 PM", ClassClock.to12Hour("13:00:00"));
        checkEquals("evening", "8:27:28 PM", ClassClock.to12Hour("20:27:28"));
        checkEquals("last minute of the day", "11:59:59 PM",
                ClassClock.to12Hour("23:59:59"));
        checkEquals("null is empty", "", ClassClock.to12Hour(null));
        checkEquals("junk passes through", "not a clock",
                ClassClock.to12Hour("not a clock"));

        checkEquals("full stamp", "2026-09-27 8:27:28 PM",
                ClassClock.stampTo12Hour("2026-09-27 20:27:28"));
        checkEquals("short stamp untouched", "oops",
                ClassClock.stampTo12Hour("oops"));

        // storage must stay 24-hour or the day filter breaks
        Calendar when = Calendar.getInstance();
        when.set(2026, Calendar.SEPTEMBER, 27, 20, 27, 28);
        checkEquals("stored stamp is 24-hour", "2026-09-27 20:27:28",
                ClassClock.storageStamp(when));
        checkEquals("day slice", "2026-09-27",
                ClassClock.dayOf(ClassClock.storageStamp(when)));
    }

    // ------------------------------------------------------------------
    // EARLY / ON TIME / LATE
    // ------------------------------------------------------------------

    private static void testTimeClassification() {
        System.out.println("\nearly / on time / late");

        ClassTimeSettings window = ClassTimeSettings.defaults();   // 08:00, -15, +10

        checkEquals("07:44 is early", "EARLY", classifyAt(window, 7, 44));
        checkEquals("07:45 is on time (window opens)", "ON TIME",
                classifyAt(window, 7, 45));
        checkEquals("08:00 is on time", "ON TIME", classifyAt(window, 8, 0));
        checkEquals("08:10 is on time (window closes)", "ON TIME",
                classifyAt(window, 8, 10));
        checkEquals("08:11 is late", "LATE", classifyAt(window, 8, 11));
        checkEquals("22:00 is late", "LATE", classifyAt(window, 22, 0));

        ClassTimeSettings afternoon = new ClassTimeSettings(13, 30, 0, 0);
        checkEquals("13:29 is early for a 13:30 class", "EARLY",
                classifyAt(afternoon, 13, 29));
        checkEquals("13:30 is on time", "ON TIME", classifyAt(afternoon, 13, 30));
        checkEquals("13:31 is late", "LATE", classifyAt(afternoon, 13, 31));
    }

    private static String classifyAt(ClassTimeSettings window, int hour, int minute) {
        Calendar moment = Calendar.getInstance();
        moment.set(2026, Calendar.SEPTEMBER, 27, hour, minute, 0);
        moment.set(Calendar.MILLISECOND, 0);
        return ClassClock.classify(moment, window);
    }

    // ------------------------------------------------------------------
    // student number parser
    // ------------------------------------------------------------------

    private static void testStudentNumberParser() {
        System.out.println("\nstudent number parser");

        checkEquals("plain number", "20253152",
                StudentNumberParser.bestGuess("20253152"));

        List<String> prefixed = StudentNumberParser.candidates("STU20253152");
        check("prefixed keeps the whole code first", prefixed.contains("STU20253152"));
        check("prefixed also offers the digits", prefixed.contains("20253152"));

        List<String> separated = StudentNumberParser.candidates("2025-3152");
        check("separated keeps the whole code", separated.contains("2025-3152"));

        checkEquals("letters only code", "ALIMEN",
                StudentNumberParser.bestGuess("ALIMEN"));

        checkEquals("json payload", "20253152", StudentNumberParser.bestGuess(
                "{\"student_id\":\"20253152\",\"name\":\"Jhon Lloyd Molino\"}"));
        checkEquals("json with a different key", "20253152",
                StudentNumberParser.bestGuess("{\"student_no\": \"20253152\"}"));
        checkEquals("query string", "20253152", StudentNumberParser.bestGuess(
                "https://cscqcph.com/verify?student_no=20253152"));
        checkEquals("query string with id", "20253152",
                StudentNumberParser.bestGuess("https://cscqcph.com/verify?id=20253152"));

        check("empty text gives nothing",
                StudentNumberParser.candidates("").isEmpty());
        check("null gives nothing",
                StudentNumberParser.candidates(null).isEmpty());

        check("a name is not treated as a student number",
                StudentNumberParser.candidates("Jhon Lloyd Molino").isEmpty());

        List<String> noisy = StudentNumberParser.candidates(
                "BT-204-20253152 LATE 08:11");
        check("noisy line still finds the number", noisy.contains("20253152"));

        check("control characters are stripped",
                StudentNumberParser.bestGuess("20253152\u0000").startsWith("20253152"));
    }

    // ------------------------------------------------------------------
    // roster csv
    // ------------------------------------------------------------------

    private static void testRosterCsvReader() throws IOException {
        System.out.println("\nroster csv reader");

        String shipped = "student_id,full_name,gmail,course,year_level,section\n"
                + "20263748,Kurt Andrei M. Acaso,,BT,1,BT-104\n"
                + "20263741,Rheign V. Acaso,,BT,1,BT-104\n";
        List<Student> simple = RosterCsvReader.read(shipped);
        checkEquals("two rows read", 2, simple.size());
        checkEquals("first name", "Kurt Andrei M. Acaso", simple.get(0).fullName);
        checkEquals("first section", "BT-104", simple.get(0).section);
        checkEquals("course column", "BT", simple.get(0).course);

        String portal = "section,student_no,surname,first_name,access_key\n"
                + "BT-204,20253152,molino,jhon lloyd,KEY-1\n"
                + "BT-104,,acaso,kurt andrei,ALIMEN\n";
        List<Student> exported = RosterCsvReader.read(portal);
        checkEquals("portal export read", 2, exported.size());
        checkEquals("name joined and titled", "Jhon Lloyd Molino",
                exported.get(0).fullName);
        checkEquals("student number kept", "20253152", exported.get(0).studentId);
        checkEquals("blank number falls back to the access key", "ALIMEN",
                exported.get(1).studentId);
        checkEquals("course from the section", "BT", exported.get(0).course);
        checkEquals("year from the section", "2", exported.get(0).yearLevel);

        String quoted = "student_id,name\n"
                + "20253152,\"Molino, Jhon Lloyd\"\n";
        List<Student> withComma = RosterCsvReader.read(quoted);
        checkEquals("a comma inside quotes does not split the row", 1, withComma.size());
        checkEquals("quoted name survives", "Molino, Jhon Lloyd",
                withComma.get(0).fullName);

        String withBom = "\uFEFFstudent_id,name\n20253152,Jhon Lloyd Molino\n";
        checkEquals("byte order mark ignored", 1, RosterCsvReader.read(withBom).size());

        String oddHeaders = "LRN,Student Name,Block\n"
                + "20253152,Jhon Lloyd Molino,BT-204\n";
        List<Student> aliased = RosterCsvReader.read(oddHeaders);
        checkEquals("other header spellings work", 1, aliased.size());
        checkEquals("LRN mapped to student id", "20253152", aliased.get(0).studentId);

        boolean refused = false;
        try {
            RosterCsvReader.read("colour,size\nred,large\n");
        } catch (IOException expected) {
            refused = true;
        }
        check("a file with no student column is refused", refused);
    }

    // ------------------------------------------------------------------
    // the barcode decoder, fed real barcodes
    // ------------------------------------------------------------------

    private static void testBarcodeDecoder() {
        System.out.println("\nbarcode decoder");

        BarcodeDecoder decoder = new BarcodeDecoder();

        // A plain blank frame must not throw and must not invent a code.
        byte[] blank = new byte[640 * 480];
        Arrays.fill(blank, (byte) 0xFF);
        checkEquals("a blank frame reads as nothing", null,
                decoder.decodeBrightnessPlane(blank, 640, 480));

        checkEquals("a null frame reads as nothing", null,
                decoder.decodeBrightnessPlane(null, 640, 480));

        // Real Code 128, the format most school ID barcodes use.
        String fromCode128 = decodeEncoded(decoder, BarcodeFormat.CODE_128, "20253152", 600, 90);
        checkEquals("Code 128 reads back", "20253152", fromCode128);

        // A shorter number, to be sure it is not a fluke of the length.
        checkEquals("Code 128 with a short number reads back", "12345",
                decodeEncoded(decoder, BarcodeFormat.CODE_128, "12345", 600, 90));

        // A letter-only code, which is what some roster rows use.
        checkEquals("Code 128 with letters reads back", "ALIMEN",
                decodeEncoded(decoder, BarcodeFormat.CODE_128, "ALIMEN", 600, 90));

        // QR codes, for the newer IDs.
        checkEquals("QR reads back", "20253152",
                decodeEncoded(decoder, BarcodeFormat.QR_CODE, "20253152", 300, 300));

        String qrJson = "{\"student_id\":\"20253152\",\"name\":\"Jhon Lloyd Molino\"}";
        String decodedJson = decodeEncoded(decoder, BarcodeFormat.QR_CODE, qrJson, 400, 400);
        checkEquals("QR with json reads back", qrJson, decodedJson);
        checkEquals("and the parser finds the number in it", "20253152",
                StudentNumberParser.bestGuess(decodedJson));

        // The full path a camera frame takes: NV21 in, text out.
        BitMatrix matrix = encode(BarcodeFormat.CODE_128, "20253152", 600, 90);
        int[] size = new int[2];
        byte[] brightness = paintBrightnessPlane(matrix, 15, 1, size);

        byte[] nv21 = new byte[size[0] * size[1] * 3 / 2];
        System.arraycopy(brightness, 0, nv21, 0, brightness.length);
        Arrays.fill(nv21, brightness.length, nv21.length, (byte) 0x80);

        checkEquals("a whole camera frame decodes", "20253152",
                decoder.decode(nv21, size[0], size[1]));
        checkEquals("a frame that is too small is refused", null,
                decoder.decode(nv21, size[0], size[1] * 4));
    }

    private static String decodeEncoded(BarcodeDecoder decoder, BarcodeFormat format,
                                        String content, int width, int height) {
        int[] size = new int[2];
        byte[] plane = paintBrightnessPlane(encode(format, content, width, height),
                15, 1, size);
        return decoder.decodeBrightnessPlane(plane, size[0], size[1]);
    }

    private static BitMatrix encode(BarcodeFormat format, String content,
                                    int width, int height) {
        try {
            if (format == BarcodeFormat.QR_CODE) {
                return new QRCodeWriter().encode(content, format, width, height);
            }
            return new Code128Writer().encode(content, format, width, height);
        } catch (Exception impossible) {
            throw new RuntimeException("could not build a test barcode", impossible);
        }
    }

    /**
     * Paints a barcode into a grayscale plane the same shape a camera frame's
     * brightness plane has: 0 is black, 255 is white.
     */
    private static byte[] paintBrightnessPlane(BitMatrix matrix, int quietZone,
                                               int scale, int[] sizeOut) {
        int matrixWidth = matrix.getWidth();
        int matrixHeight = matrix.getHeight();
        int planeWidth = (matrixWidth + quietZone * 2) * scale;
        int planeHeight = (matrixHeight + quietZone * 2) * scale;

        byte[] plane = new byte[planeWidth * planeHeight];
        Arrays.fill(plane, (byte) 0xFF);

        for (int y = 0; y < matrixHeight; y++) {
            for (int x = 0; x < matrixWidth; x++) {
                if (!matrix.get(x, y)) continue;
                int startX = (x + quietZone) * scale;
                int startY = (y + quietZone) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int rowStart = (startY + dy) * planeWidth + startX;
                    for (int dx = 0; dx < scale; dx++) {
                        plane[rowStart + dx] = 0;
                    }
                }
            }
        }

        sizeOut[0] = planeWidth;
        sizeOut[1] = planeHeight;
        return plane;
    }

    // ------------------------------------------------------------------
    // the roster file that ships in the apk
    // ------------------------------------------------------------------

    private static void testRosterFile(String[] args) throws IOException {
        System.out.println("\nbundled roster");

        if (args.length == 0) {
            System.out.println("  SKIP  no roster path given");
            return;
        }

        java.io.File file = new java.io.File(args[0]);
        if (!file.exists()) {
            System.out.println("  SKIP  roster file not found: " + args[0]);
            return;
        }

        java.io.InputStream stream = new java.io.FileInputStream(file);
        List<Student> students;
        try {
            students = RosterCsvReader.read(stream);
        } finally {
            stream.close();
        }

        check("the shipped roster loads", students.size() > 50);
        System.out.println("        " + students.size() + " students");

        int blankNames = 0;
        int blankIds = 0;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int duplicates = 0;
        for (Student student : students) {
            if (student.fullName.isEmpty()) blankNames++;
            if (student.studentId.isEmpty()) blankIds++;
            if (!seen.add(student.studentId)) duplicates++;
        }

        checkEquals("no blank student numbers", 0, blankIds);
        checkEquals("no blank names", 0, blankNames);
        checkEquals("no duplicate student numbers", 0, duplicates);
    }

    // ------------------------------------------------------------------
    // tiny test helpers
    // ------------------------------------------------------------------

    private static String join(int[] pair) {
        if (pair == null) return null;
        return pair[0] + ":" + pair[1];
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    private static void checkEquals(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            System.out.println("        expected <" + expected + "> but got <" + actual + ">");
        }
        check(name, same);
    }

    private ScanGateLogicTests() {
    }
}
