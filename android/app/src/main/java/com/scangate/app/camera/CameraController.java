package com.scangate.app.camera;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.util.List;

/**
 * Opens the phone camera and pushes preview frames to a listener.
 *
 * This uses android.hardware.Camera, the original camera API. It is marked
 * deprecated in modern Android, but it still works on every phone from
 * Android 7 up to the newest ones, and it needs no extra libraries at all.
 * The newer Camera2 API would mean a lot more code for no benefit here,
 * since all we want is a stream of brightness frames to hand to ZXing.
 *
 * Preview frames arrive in NV21 format: brightness first, colour after.
 * Barcodes only need the brightness part, which is why the decoder can
 * simply take the front of the buffer and ignore the rest.
 *
 * Threading note: onFrame runs on the same thread that opened the camera,
 * normally the screen's main thread. A listener that wants to keep the
 * bytes must copy them before returning, because the buffer is handed
 * straight back to the camera as soon as onFrame returns.
 */
public class CameraController {

    /** Receives every preview frame. Copy anything you need before returning. */
    public interface FrameListener {
        void onFrame(byte[] nv21Frame, int width, int height);
    }

    /** Big enough to read a barcode, small enough to decode fast. */
    private static final long TARGET_PIXELS = 1280L * 720L;
    private static final long MAX_PIXELS = 1920L * 1080L;
    private static final long MIN_PIXELS = 640L * 480L;

    private Camera camera;
    private int cameraId;
    private int previewWidth;
    private int previewHeight;
    private byte[] frameBuffer;
    private FrameListener frameListener;
    private boolean torchOn;

    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] frame, Camera source) {
            FrameListener listener = frameListener;
            if (listener != null && frame != null) {
                try {
                    listener.onFrame(frame, previewWidth, previewHeight);
                } catch (RuntimeException ignored) {
                    // A bad frame must never kill the preview.
                }
            }
            if (source != null) {
                try {
                    source.addCallbackBuffer(frame);
                } catch (RuntimeException ignored) {
                    // Camera already gone.
                }
            }
        }
    };

    public boolean isOpen() {
        return camera != null;
    }

    public int getPreviewWidth() {
        return previewWidth;
    }

    public int getPreviewHeight() {
        return previewHeight;
    }

    public boolean isTorchOn() {
        return torchOn;
    }

    /**
     * Opens the back camera and starts the preview.
     *
     * @param displayRotation the screen rotation, from
     *                        getWindowManager().getDefaultDisplay().getRotation()
     * @return true when the preview is actually running
     */
    public boolean start(SurfaceHolder holder, int displayRotation, FrameListener listener) {
        stop();
        try {
            camera = openBackCamera();
            if (camera == null) return false;

            Camera.Parameters parameters = camera.getParameters();
            choosePreviewSize(parameters);
            parameters.setPreviewSize(previewWidth, previewHeight);
            parameters.setPreviewFormat(ImageFormat.NV21);
            chooseFocusMode(parameters);
            camera.setParameters(parameters);

            camera.setDisplayOrientation(displayOrientationFor(displayRotation));
            camera.setPreviewDisplay(holder);

            frameListener = listener;
            frameBuffer = new byte[previewWidth * previewHeight * 3 / 2];
            camera.addCallbackBuffer(frameBuffer);
            camera.setPreviewCallbackWithBuffer(previewCallback);
            camera.startPreview();
            return true;
        } catch (IOException cameraBusy) {
            stop();
            return false;
        } catch (RuntimeException cameraUnavailable) {
            stop();
            return false;
        }
    }

    /** Closes the camera. Safe to call when nothing is open. */
    public void stop() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
            } catch (RuntimeException ignored) {
            }
            try {
                camera.stopPreview();
            } catch (RuntimeException ignored) {
            }
            try {
                camera.release();
            } catch (RuntimeException ignored) {
            }
            camera = null;
        }
        frameListener = null;
        frameBuffer = null;
        torchOn = false;
    }

    /**
     * Turns the phone's light on or off.
     *
     * @return false when this phone has no torch, so the button can say so
     */
    public boolean setTorch(boolean on) {
        if (camera == null) return false;
        try {
            Camera.Parameters parameters = camera.getParameters();
            List<String> flashModes = parameters.getSupportedFlashModes();
            if (flashModes == null
                    || !flashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                return false;
            }
            parameters.setFlashMode(on
                    ? Camera.Parameters.FLASH_MODE_TORCH
                    : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(parameters);
            torchOn = on;
            return true;
        } catch (RuntimeException torchRefused) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // picking the camera and its settings
    // ------------------------------------------------------------------

    private Camera openBackCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        int count = Camera.getNumberOfCameras();

        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                cameraId = i;
                return Camera.open(i);
            }
        }
        // No back camera reported (rare). Fall back to whatever exists.
        if (count > 0) {
            cameraId = 0;
            return Camera.open(0);
        }
        return null;
    }

    /**
     * Picks a preview size near 1280x720. Any bigger is wasted work, because
     * the decoder has to scan every pixel of every frame, and any smaller
     * makes a small barcode too blurry to read.
     */
    private void choosePreviewSize(Camera.Parameters parameters) {
        List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
        if (sizes == null || sizes.isEmpty()) {
            previewWidth = 640;
            previewHeight = 480;
            return;
        }

        Camera.Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < sizes.size(); i++) {
            Camera.Size size = sizes.get(i);
            long pixels = (long) size.width * size.height;
            if (pixels < MIN_PIXELS || pixels > MAX_PIXELS) continue;

            long score = Math.abs(pixels - TARGET_PIXELS);
            // Prefer a landscape shape, which is how barcodes are printed.
            if (size.width < size.height) score += TARGET_PIXELS / 4;

            if (score < bestScore) {
                bestScore = score;
                best = size;
            }
        }

        if (best == null) best = sizes.get(0);
        previewWidth = best.width;
        previewHeight = best.height;
    }

    /** Continuous autofocus when the phone offers it, plain autofocus otherwise. */
    private void chooseFocusMode(Camera.Parameters parameters) {
        List<String> modes = parameters.getSupportedFocusModes();
        if (modes == null) return;
        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        } else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        }
    }

    /**
     * The standard camera rotation formula. The screen is locked to portrait
     * in the manifest, so this mostly resolves to a straight 90 degrees on a
     * phone and 0 on a tablet, but it also handles the front camera case.
     */
    private int displayOrientationFor(int displayRotation) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);

        int degrees = 0;
        switch (displayRotation) {
            case android.view.Surface.ROTATION_90:
                degrees = 90;
                break;
            case android.view.Surface.ROTATION_180:
                degrees = 180;
                break;
            case android.view.Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                degrees = 0;
                break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }
}
