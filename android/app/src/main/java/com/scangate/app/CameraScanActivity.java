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
import com.scangate.app.logic.ocr.GrayImage;
import com.scangate.app.logic.ocr.PrintedNumberReader;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The camera screen. This is the part that reads the card.
 *
 * There are two things worth reading on a student ID, and this screen tries
 * both without being asked which:
 *
 *   the barcode       if the card has one printed on it
 *   the printed number, read off the card as characters
 *
 * They take turns rather than both running on every frame. A barcode decode
 * costs about as long as a printed number read, so doing both on each frame
 * would halve how often either one gets a chance. Alternating gives each of
 * them a few tries a second, which is plenty for a card being held still, and
 * the first one to find something ends the screen.
 *
 * The other reason for two paths: plenty of school ID cards have no readable
 * barcode at all. Ours does not. The QR on it points at the school's Facebook
 * page and holds no student number, and the real identity lives on an RFID chip
 * that no camera can see. For a card like that, reading the printed number is
 * the only thing a phone can actually do.
 *
 * The reading happens on a background thread. Frames arrive on the camera's own
 * thread, and only one piece of work is allowed in flight at a time, so a slow
 * read never piles up behind itself and stutters the preview.
 */
public class CameraScanActivity extends Activity
        implements SurfaceHolder.Callback, CameraController.FrameListener {

    /** The best thing that was read. */
    public static final String EXTRA_SCANNED_TEXT = "scanned_text";

    /** Other things worth trying, when the reader was unsure. */
    public static final String EXTRA_SCANNED_ALTERNATIVES = "scanned_alternatives";

    /** Which of the two paths found it: "barcode" or "printed". */
    public static final String EXTRA_SCANNED_SOURCE = "scanned_source";

    public static final String SOURCE_BARCODE = "barcode";
    public static final String SOURCE_PRINTED = "printed";

    private static final int REQUEST_CAMERA_PERMISSION = 101;

    /**
     * The shortest gap between handing work to the reader thread.
     *
     * The real limit is how long a read takes, not this number, because only
     * one runs at a time. This is here to stop a burst of frames queueing up
     * behind each other on a fast phone.
     */
    private static final long MILLISECONDS_BETWEEN_DECODES = 140L;
    private static final long HINT_AFTER_QUIET_MILLISECONDS = 8000L;

    /** How many unsure readings to pass on besides the best one. */
    private static final int MAX_ALTERNATIVES = 4;

    private SurfaceView previewView;
    private TextView hintText;
    private TextView torchButton;

    private CameraController cameraController;
    private BarcodeDecoder barcodeDecoder;
    private PrintedNumberReader numberReader;
    private ScanFeedback feedback;

    /** Kept and pointed at each new frame, so the reader's scratch space survives. */
    private GrayImage grayImage;

    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler();

    private volatile boolean decodeInFlight;
    private boolean finished;
    private boolean surfaceReady;
    private boolean resumed;
    private boolean permissionGranted;
    private boolean printedNumberTurn;
    private long lastDecodeStartedAt;
    private byte[] frameCopy;

    private final Runnable quietHint = new Runnable() {
        @Override
        public void run() {
            if (!finished) hintText.setText(R.string.camera_nothing_read);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_scan);

        // A gate scanner is held in one hand for a while, so keep the screen awake.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        previewView = findViewById(R.id.cameraPreview);
        hintText = findViewById(R.id.tvCameraHint);
        torchButton = findViewById(R.id.btnTorch);
        TextView cancelButton = findViewById(R.id.btnCancelScan);

        cameraController = new CameraController();
        barcodeDecoder = new BarcodeDecoder();
        numberReader = new PrintedNumberReader();
        feedback = new ScanFeedback();

        previewView.getHolder().addCallback(this);
        torchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleTorch();
            }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

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
    // frames
    // ------------------------------------------------------------------

    /**
     * Called on the camera's thread for every preview frame.
     *
     * Two things matter here. First, the camera reuses its buffer the moment
     * this method returns, so the bytes have to be copied before anything else
     * can look at them. Second, only one read runs at a time. If one is already
     * going, the frame is dropped, which keeps the preview smooth instead of
     * piling up work behind a slow frame.
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

        printedNumberTurn = !printedNumberTurn;
        final boolean readPrintedNumber = printedNumberTurn;

        decodeInFlight = true;
        decodeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (readPrintedNumber) {
                        lookForPrintedNumber(copiedFrame, frameWidth, frameHeight);
                    } else {
                        lookForBarcode(copiedFrame, frameWidth, frameHeight);
                    }
                } finally {
                    decodeInFlight = false;
                }
            }
        });
    }

    private void lookForBarcode(byte[] frame, int width, int height) {
        String text = barcodeDecoder.decode(frame, width, height);
        if (text != null) {
            uiHandler.post(new ScanFound(text, new String[0], SOURCE_BARCODE));
        }
    }

    private void lookForPrintedNumber(byte[] frame, int width, int height) {
        if (grayImage == null || grayImage.width() != width || grayImage.height() != height) {
            grayImage = new GrayImage(frame, width, height);
        } else {
            // The brightness plane of an NV21 frame is the front of the buffer,
            // so the reader works straight off it with no copy.
            grayImage.wrap(frame);
        }

        List<PrintedNumberReader.Read> reads = numberReader.read(grayImage);
        if (reads.isEmpty()) return;

        String best = reads.get(0).digits;
        String[] alternatives = new String[Math.min(reads.size() - 1, MAX_ALTERNATIVES)];
        for (int i = 0; i < alternatives.length; i++) {
            alternatives[i] = reads.get(i + 1).digits;
        }
        uiHandler.post(new ScanFound(best, alternatives, SOURCE_PRINTED));
    }

    /** Hands what was read back to the home screen, which does the roster lookup. */
    private class ScanFound implements Runnable {

        private final String text;
        private final String[] alternatives;
        private final String source;

        ScanFound(String text, String[] alternatives, String source) {
            this.text = text;
            this.alternatives = alternatives;
            this.source = source;
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
            result.putExtra(EXTRA_SCANNED_ALTERNATIVES, alternatives);
            result.putExtra(EXTRA_SCANNED_SOURCE, source);
            setResult(RESULT_OK, result);
            finish();
        }
    }
}
