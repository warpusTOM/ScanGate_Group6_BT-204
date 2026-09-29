package com.scangate.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.scangate.app.camera.CameraController;
import com.scangate.app.camera.ScanFeedback;
import com.scangate.app.logic.BarcodeDecoder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The camera screen. This is the part that reads the ID.
 *
 * How it works, start to finish:
 *
 *   1. Ask for the camera permission the first time it is opened.
 *   2. Show the live preview on a SurfaceView.
 *   3. The camera hands over about 30 frames a second in NV21 format.
 *   4. Most of those frames are thrown away on purpose. Only about five a
 *      second are copied and sent to the decoder, because decoding is the
 *      slow part and a hand holding a card is not moving that fast.
 *   5. The decoder runs on a background thread so the preview never stutters.
 *   6. The first frame that contains a readable barcode ends the screen.
 *      It beeps, buzzes, and hands the raw text back to the home screen,
 *      which is where the roster lookup happens.
 *
 * A note on why the camera is stopped before finishing: some phones refuse
 * to hand the camera to the next app if the previous one has not released
 * it, and the result would be a black preview next time.
 */
public class CameraScanActivity extends Activity
        implements SurfaceHolder.Callback, CameraController.FrameListener {

    /** Key for the raw text that came out of the barcode. */
    public static final String EXTRA_SCANNED_TEXT = "scanned_text";

    private static final int REQUEST_CAMERA_PERMISSION = 101;
    private static final long MILLISECONDS_BETWEEN_DECODES = 180L;
    private static final long HINT_AFTER_QUIET_MILLISECONDS = 6000L;

    private SurfaceView previewView;
    private TextView hintText;
    private TextView torchButton;

    private CameraController cameraController;
    private BarcodeDecoder decoder;
    private ScanFeedback feedback;

    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler();

    private volatile boolean decodeInFlight;
    private boolean finished;
    private boolean surfaceReady;
    private boolean resumed;
    private boolean permissionGranted;
    private long lastDecodeStartedAt;
    private byte[] frameCopy;

    private final Runnable quietHint = new Runnable() {
        @Override
        public void run() {
            if (!finished) hintText.setText(R.string.camera_no_barcode);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_scan);

        // A gate scanner is used in one hand for a while, so keep the screen awake.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        previewView = findViewById(R.id.cameraPreview);
        hintText = findViewById(R.id.tvCameraHint);
        torchButton = findViewById(R.id.btnTorch);
        TextView cancelButton = findViewById(R.id.btnCancelScan);

        cameraController = new CameraController();
        decoder = new BarcodeDecoder();
        feedback = new ScanFeedback();

        previewView.getHolder().addCallback(this);
        torchButton.setOnClickListener(view -> toggleTorch());
        cancelButton.setOnClickListener(view -> finish());

        permissionGranted = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (permissionGranted) {
            hintText.setText(R.string.camera_starting);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        }
    }

    // ------------------------------------------------------------------
    // camera permission
    // ------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            permissionGranted = true;
            tryStartCamera();
        } else {
            hintText.setText(R.string.camera_permission_needed);
            uiHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }, 2200L);
        }
    }

    // ------------------------------------------------------------------
    // surface lifecycle
    // ------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        tryStartCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Nothing to do. The preview size is chosen inside CameraController.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        cameraController.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        tryStartCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        cameraController.stop();
        uiHandler.removeCallbacks(quietHint);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(quietHint);
        cameraController.stop();
        feedback.release();
        decodeExecutor.shutdownNow();
    }

    /**
     * Starts the preview only when every condition is met. Called from three
     * places (surface ready, screen resumed, permission granted) because the
     * order they happen in is not guaranteed.
     */
    private void tryStartCamera() {
        if (finished || !surfaceReady || !resumed || !permissionGranted) return;
        if (cameraController.isOpen()) return;

        boolean started = cameraController.start(
                previewView.getHolder(), displayRotation(), this);

        hintText.setText(started ? R.string.camera_hint : R.string.camera_failed);
        if (started) {
            uiHandler.removeCallbacks(quietHint);
            uiHandler.postDelayed(quietHint, HINT_AFTER_QUIET_MILLISECONDS);
        }
    }

    private int displayRotation() {
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    private void toggleTorch() {
        boolean wantOn = !cameraController.isTorchOn();
        if (cameraController.setTorch(wantOn)) {
            torchButton.setText(wantOn
                    ? R.string.button_torch_off
                    : R.string.button_torch_on);
        } else {
            torchButton.setText(R.string.button_torch_on);
        }
    }

    // ------------------------------------------------------------------
    // frames and decoding
    // ------------------------------------------------------------------

    /**
     * Called on the camera's thread for every preview frame.
     *
     * Two things matter here. First, the camera reuses its buffer the moment
     * this method returns, so the bytes have to be copied before the decoder
     * can look at them. Second, only one decode runs at a time - if one is
     * already going, the frame is dropped, which keeps the preview smooth
     * instead of piling up work.
     */
    @Override
    public void onFrame(byte[] frame, int width, int height) {
        if (finished || decodeInFlight) return;

        long now = System.currentTimeMillis();
        if (now - lastDecodeStartedAt < MILLISECONDS_BETWEEN_DECODES) return;
        lastDecodeStartedAt = now;

        if (frameCopy == null || frameCopy.length != frame.length) {
            frameCopy = new byte[frame.length];
        }
        System.arraycopy(frame, 0, frameCopy, 0, frame.length);

        final byte[] copiedFrame = frameCopy;
        final int frameWidth = width;
        final int frameHeight = height;

        decodeInFlight = true;
        decodeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String text = decoder.decode(copiedFrame, frameWidth, frameHeight);
                    if (text != null) {
                        uiHandler.post(new BarcodeFound(text));
                    }
                } finally {
                    decodeInFlight = false;
                }
            }
        });
    }

    /** Hands the raw text back to the home screen, which does the lookup. */
    private class BarcodeFound implements Runnable {

        private final String text;

        BarcodeFound(String text) {
            this.text = text;
        }

        @Override
        public void run() {
            if (finished) return;
            finished = true;

            cameraController.stop();
            feedback.playCaptured();
            feedback.vibrate(CameraScanActivity.this, 60L);

            Intent result = new Intent();
            result.putExtra(EXTRA_SCANNED_TEXT, text);
            setResult(RESULT_OK, result);
            finish();
        }
    }
}
