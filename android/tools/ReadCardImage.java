import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.HybridBinarizer;
import com.scangate.app.logic.BarcodeDecoder;
import com.scangate.app.logic.ocr.PrintedNumberReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a card out of a picture file, both ways.
 *
 * This is what the phone does, except the frame is a JPEG on disk instead of a
 * camera buffer. Handy for two things: checking what is actually printed on a
 * student ID, and measuring the printed number reader against a real card
 * rather than only the synthetic ones in the test harness.
 *
 * It tries, in order:
 *
 *   the barcode      every format the phone app supports, on the picture as it
 *                    is and after several preparations
 *   the printed number   the same reader the phone runs, over the same picture
 *
 * The preparations matter for photographs. A phone photo of a card is soft,
 * unevenly lit and slightly tilted, which is much worse than a live camera
 * frame held steady. So the picture is also tried upscaled, contrast stretched,
 * inverted and cropped down to the ink.
 *
 * Usage:
 *     java ReadCardImage <image> [image...]
 */
public final class ReadCardImage {

    private static final MultiFormatReader READER = new MultiFormatReader();

    private static final Map<DecodeHintType, Object> HINTS =
            new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

    static {
        List<BarcodeFormat> formats = new ArrayList<BarcodeFormat>();
        formats.add(BarcodeFormat.QR_CODE);
        formats.add(BarcodeFormat.DATA_MATRIX);
        formats.add(BarcodeFormat.CODE_128);
        formats.add(BarcodeFormat.CODE_39);
        formats.add(BarcodeFormat.CODE_93);
        formats.add(BarcodeFormat.CODABAR);
        formats.add(BarcodeFormat.ITF);
        formats.add(BarcodeFormat.EAN_13);
        formats.add(BarcodeFormat.EAN_8);
        formats.add(BarcodeFormat.UPC_A);
        formats.add(BarcodeFormat.UPC_E);
        HINTS.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        HINTS.put(DecodeHintType.CHARACTER_SET, "UTF-8");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: ReadCardImage <image> [image...]");
            System.exit(2);
        }
        for (String path : args) {
            readFile(new File(path));
        }
    }

    private static void readFile(File file) throws Exception {
        System.out.println("=====================================================");
        System.out.println("file: " + file.getAbsolutePath());

        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            System.out.println("  could not read this as an image");
            return;
        }
        System.out.println("size: " + image.getWidth() + " x " + image.getHeight());

        System.out.println();
        System.out.println("barcode");
        Map<String, Result> hits = new LinkedHashMap<String, Result>();
        tryBarcode(hits, "direct", image);
        tryBarcode(hits, "upscale x2", scale(image, 2));
        tryBarcode(hits, "upscale x3", scale(image, 3));
        tryBarcode(hits, "contrast", stretchContrast(image));
        tryBarcode(hits, "contrast x2", scale(stretchContrast(image), 2));
        tryBarcode(hits, "inverted", invert(image));

        BufferedImage cropped = cropToInk(image);
        if (cropped != null && cropped != image) {
            System.out.println("  cropped to the ink: "
                    + cropped.getWidth() + " x " + cropped.getHeight());
            tryBarcode(hits, "cropped x2", scale(cropped, 2));
            tryBarcode(hits, "cropped x3", scale(cropped, 3));
        }

        if (hits.isEmpty()) {
            System.out.println("  nothing readable");
        } else {
            Result first = hits.values().iterator().next();
            System.out.println("  FORMAT : " + first.getBarcodeFormat());
            System.out.println("  TEXT   : " + quote(first.getText()));
            System.out.println("  LENGTH : " + first.getText().length() + " characters");
            Object ecc = first.getResultMetadata() == null ? null
                    : first.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL);
            if (ecc != null) System.out.println("  ECC    : " + ecc);
            System.out.println("  read by: " + join(hits.keySet()));
        }

        System.out.println();
        System.out.println("printed number");
        List<PrintedNumberReader.Read> reads = new PrintedNumberReader()
                .read(toLuminance(image), image.getWidth(), image.getHeight());
        if (reads.isEmpty()) {
            System.out.println("  nothing readable");
        } else {
            for (int i = 0; i < reads.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + reads.get(i));
            }
        }

        // The app's own decoder, to confirm the class that ships handles this
        // exact picture too.
        String viaApp = new BarcodeDecoder().decodeBrightnessPlane(
                toLuminance(image), image.getWidth(), image.getHeight());
        if (viaApp != null) {
            System.out.println();
            System.out.println("app BarcodeDecoder: " + quote(viaApp));
        }
    }

    // ------------------------------------------------------------------
    // barcode
    // ------------------------------------------------------------------

    private static void tryBarcode(Map<String, Result> hits, String label, BufferedImage image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            Result result = READER.decode(new BinaryBitmap(new HybridBinarizer(source)), HINTS);
            if (result != null) {
                hits.put(label, result);
                System.out.println("  " + label + ": READ (" + result.getBarcodeFormat() + ")");
            }
        } catch (Exception noRead) {
            // expected for most preparations
        }
    }

    // ------------------------------------------------------------------
    // image helpers
    // ------------------------------------------------------------------

    private static BufferedImage scale(BufferedImage image, int factor) {
        int width = image.getWidth() * factor;
        int height = image.getHeight() * factor;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, image.getRGB(x / factor, y / factor));
            }
        }
        return out;
    }

    private static BufferedImage toGrayscale(BufferedImage image) {
        BufferedImage out = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D graphics = out.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return out;
    }

    /** Stretches the histogram so the darkest pixel is black and the lightest is white. */
    private static BufferedImage stretchContrast(BufferedImage image) {
        BufferedImage gray = toGrayscale(image);
        int width = gray.getWidth();
        int height = gray.getHeight();

        int low = 255;
        int high = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = gray.getRGB(x, y) & 0xFF;
                if (value < low) low = value;
                if (value > high) high = value;
            }
        }
        if (high <= low) return gray;

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = gray.getRGB(x, y) & 0xFF;
                int stretched = (value - low) * 255 / (high - low);
                stretched = Math.max(0, Math.min(255, stretched));
                out.setRGB(x, y, (stretched << 16) | (stretched << 8) | stretched);
            }
        }
        return out;
    }

    private static BufferedImage invert(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, ~image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return out;
    }

    /** Trims the picture down to the dark ink, which is where the printing is. */
    private static BufferedImage cropToInk(BufferedImage image) {
        BufferedImage gray = toGrayscale(image);
        int width = gray.getWidth();
        int height = gray.getHeight();

        long sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                sum += gray.getRGB(x, y) & 0xFF;
            }
        }
        int mean = (int) (sum / (width * (long) height));
        int threshold = (int) (mean * 0.72);

        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((gray.getRGB(x, y) & 0xFF) < threshold) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX <= minX || maxY <= minY) return image;

        int margin = Math.max(8, (maxX - minX) / 20);
        minX = Math.max(0, minX - margin);
        minY = Math.max(0, minY - margin);
        maxX = Math.min(width - 1, maxX + margin);
        maxY = Math.min(height - 1, maxY + margin);

        return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
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

    // ------------------------------------------------------------------
    // printing helpers
    // ------------------------------------------------------------------

    private static String join(java.util.Collection<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(", ");
            out.append(part);
        }
        return out.toString();
    }

    private static String quote(String text) {
        if (text == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20) out.append(String.format("\\u%04X", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private ReadCardImage() {
    }
}
