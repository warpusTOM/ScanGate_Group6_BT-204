import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.HybridBinarizer;
import com.scangate.app.logic.BarcodeDecoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a barcode or QR code out of a picture file.
 *
 * This is the same job BarcodeDecoder does on a live camera frame, except the
 * frame is a JPEG on disk instead of a preview buffer. Useful for checking a
 * card without a phone: photograph the ID, run this, see what the code holds.
 *
 * A phone camera gets about thirty tries a second at a moving card. Here we get
 * one still picture, so this tries harder. It runs the image through several
 * preparations and reports every one that reads:
 *
 *   direct       the picture as it is
 *   upscale x2   doubled with nearest neighbour, for small or soft codes
 *   upscale x3   tripled, same reason
 *   contrast     histogram stretched to full black and white
 *   contrast x2  contrast stretch then doubled
 *   inverted     dark and light swapped, for a code printed white on black
 *   cropped      trimmed to the code's bounding box, then doubled
 *
 * The last one helps most on photos of paper, where the code is a small part of
 * the frame and the rest is desk.
 *
 * Usage:
 *     java -cp "<classes>;<zxing>" DecodeImageBarcode <image> [image...]
 */
public final class DecodeImageBarcode {

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
        hints(formats);
    }

    private static void hints(List<BarcodeFormat> formats) {
        HINTS.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        HINTS.put(DecodeHintType.CHARACTER_SET, "UTF-8");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: DecodeImageBarcode <image> [image...]");
            System.exit(2);
        }

        for (String path : args) {
            decodeFile(new File(path));
        }
    }

    private static void decodeFile(File file) throws Exception {
        System.out.println("=====================================================");
        System.out.println("file: " + file.getAbsolutePath());

        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            System.out.println("  could not read this as an image");
            return;
        }
        System.out.println("size: " + image.getWidth() + " x " + image.getHeight());

        Map<String, Result> hits = new LinkedHashMap<String, Result>();

        tryReading(hits, "direct", image);
        tryReading(hits, "upscale x2", scale(image, 2));
        tryReading(hits, "upscale x3", scale(image, 3));
        tryReading(hits, "contrast", stretchContrast(image));
        tryReading(hits, "contrast x2", scale(stretchContrast(image), 2));

        tryReading(hits, "inverted", invert(image));

        BufferedImage cropped = cropToCode(image);
        if (cropped != null && cropped != image) {
            System.out.println("cropped to the code: "
                    + cropped.getWidth() + " x " + cropped.getHeight());
            tryReading(hits, "cropped x2", scale(cropped, 2));
            tryReading(hits, "cropped x3", scale(cropped, 3));
        }

        // Now the same picture through the app's own decoder, to prove the
        // class that runs on the phone handles this card too.
        tryAppDecoder(image);
        tryAppDecoder(scale(image, 2));
        tryAppDecoder(stretchContrast(image));

        System.out.println();
        if (hits.isEmpty()) {
            System.out.println("RESULT: nothing readable in this picture");
            return;
        }

        Result first = hits.values().iterator().next();
        System.out.println("RESULT: " + first.getBarcodeFormat());
        System.out.println("TEXT  : " + quote(first.getText()));
        System.out.println("LENGTH: " + first.getText().length() + " characters");
        System.out.println("raw bytes: " + toHex(first.getRawBytes()));

        Object ecc = first.getResultMetadata() == null ? null
                : first.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL);
        if (ecc != null) System.out.println("error correction level: " + ecc);

        System.out.println("read by: " + String.join(", ", hits.keySet()));

        // byte segments matter when the payload is not plain text
        if (first.getResultMetadata() != null) {
            Object segments = first.getResultMetadata().get(ResultMetadataType.BYTE_SEGMENTS);
            if (segments instanceof List) {
                for (Object segment : (List<?>) segments) {
                    if (segment instanceof byte[]) {
                        System.out.println("byte segment: " + toHex((byte[]) segment));
                    }
                }
            }
        }
    }

    private static void tryReading(Map<String, Result> hits, String label,
                                   BufferedImage image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            Result result = READER.decode(
                    new BinaryBitmap(new HybridBinarizer(source)), HINTS);
            if (result != null) {
                hits.put(label, result);
                System.out.println("  " + label + ": READ (" + result.getBarcodeFormat() + ")");
            }
        } catch (Exception noRead) {
            // expected for most preparations
        }
    }

    private static void tryAppDecoder(BufferedImage image) {
        BufferedImage gray = toGrayscale(image);
        int width = gray.getWidth();
        int height = gray.getHeight();
        byte[] yPlane = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                yPlane[y * width + x] = (byte) (gray.getRGB(x, y) & 0xFF);
            }
        }
        BarcodeDecoder decoder = new BarcodeDecoder();
        String text = decoder.decodeBrightnessPlane(yPlane, width, height);
        if (text != null) {
            System.out.println("  app BarcodeDecoder: READ -> " + quote(text));
        }
    }

    // ------------------------------------------------------------------
    // image helpers
    // ------------------------------------------------------------------

    private static int[] toPixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(),
                null, 0, image.getWidth());
    }

    /** Swaps dark and light, for a code printed white on black. */
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
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
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

    /** Trims the picture down to the dark ink, which is where the code is. */
    private static BufferedImage cropToCode(BufferedImage image) {
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

    // ------------------------------------------------------------------
    // printing helpers
    // ------------------------------------------------------------------

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

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "(none)";
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02X", bytes[i]));
        }
        return out.toString();
    }

    private DecodeImageBarcode() {
    }
}
