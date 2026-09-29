package com.scangate.app.logic;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a barcode out of one camera frame.
 *
 * This class knows nothing about Android. It takes a raw brightness plane
 * and returns text, which is why it can be tested on a normal computer with
 * a barcode image generated in memory.
 *
 * About the input: android.hardware.Camera hands preview frames over in NV21
 * format. An NV21 frame is one big block of brightness bytes (width * height
 * of them), followed by a smaller block of colour bytes. Barcodes only care
 * about brightness, so the decoder copies just the first block and ignores
 * the colour part.
 *
 * The scanning library is ZXing ("zebra crossing"), the same open-source
 * barcode reader that most Android scanner apps are built on. It is a plain
 * Java library with no Android pieces, which is why the APK stays small.
 */
public final class BarcodeDecoder {

    /**
     * The barcode types a school ID is likely to use. Left out on purpose:
     * Aztec and PDF417, which almost never appear on a student card and cost
     * real time to try on every frame.
     */
    private static final BarcodeFormat[] SUPPORTED_FORMATS = {
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.CODE_93,
            BarcodeFormat.CODABAR,
            BarcodeFormat.ITF,
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E,
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX
    };

    private final MultiFormatReader reader = new MultiFormatReader();
    private final Map<DecodeHintType, Object> hints =
            new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

    /** Reused between frames so we are not allocating a megabyte per frame. */
    private byte[] brightnessPlane;

    public BarcodeDecoder() {
        List<BarcodeFormat> formats = new ArrayList<BarcodeFormat>();
        for (int i = 0; i < SUPPORTED_FORMATS.length; i++) {
            formats.add(SUPPORTED_FORMATS[i]);
        }
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
    }

    /**
     * @param nv21Frame  one preview frame straight from the camera
     * @param width      frame width in pixels
     * @param height     frame height in pixels
     * @return the text inside the barcode, or null when this frame has none
     */
    public String decode(byte[] nv21Frame, int width, int height) {
        if (nv21Frame == null || width <= 0 || height <= 0) return null;

        int brightnessBytes = width * height;
        if (nv21Frame.length < brightnessBytes) return null;

        if (brightnessPlane == null || brightnessPlane.length != brightnessBytes) {
            brightnessPlane = new byte[brightnessBytes];
        }
        System.arraycopy(nv21Frame, 0, brightnessPlane, 0, brightnessBytes);

        return decodeBrightnessPlane(brightnessPlane, width, height);
    }

    /**
     * Decodes a bare brightness plane. Split out from decode() so the test
     * harness can feed it a plane built from a generated barcode image.
     */
    public String decodeBrightnessPlane(byte[] brightness, int width, int height) {
        if (brightness == null || brightness.length < width * height) return null;
        try {
            LuminanceSource source = new PlanarYUVLuminanceSource(
                    brightness, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decode(bitmap, hints);
            return result == null ? null : result.getText();
        } catch (ReaderException noBarcodeInThisFrame) {
            // Expected on most frames. Nothing to report, just keep looking.
            return null;
        } catch (RuntimeException unexpected) {
            // A damaged frame should never take the whole preview down.
            return null;
        }
    }
}
