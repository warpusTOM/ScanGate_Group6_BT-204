package com.scangate.app.camera;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Vibrator;

/**
 * The beep and the buzz.
 *
 * At a gate you are not always looking at the screen, so a scan needs to be
 * audible. A short high tone means the number was read and found, a lower
 * double tone means the barcode was read but the student is not in the roster,
 * and the phone buzzes on every read.
 *
 * The tones come from ToneGenerator, which is built into Android, so there is
 * no sound file to ship.
 */
public class ScanFeedback {

    private static final int VOLUME_PERCENT = 90;
    private static final int BEEP_MILLISECONDS = 160;

    private ToneGenerator toneGenerator;

    public ScanFeedback() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_PERCENT);
        } catch (RuntimeException noAudio) {
            toneGenerator = null;   // silent phone, not a problem
        }
    }

    /** A barcode was captured. A short high tone. */
    public void playCaptured() {
        startTone(ToneGenerator.TONE_PROP_ACK);
    }

    /** The number was read but the student is not registered. A low double tone. */
    public void playNotFound() {
        startTone(ToneGenerator.TONE_PROP_NACK);
    }

    /** Buzzes the phone. Does nothing if it has no vibrator. */
    public void vibrate(Context context, long milliseconds) {
        if (context == null) return;
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                        milliseconds, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(milliseconds);
            }
        } catch (RuntimeException ignored) {
            // Vibration permission missing or the phone refused. Not fatal.
        }
    }

    /** Call this when leaving the camera screen. */
    public void release() {
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (RuntimeException ignored) {
            }
            toneGenerator = null;
        }
    }

    private void startTone(int toneType) {
        if (toneGenerator == null) return;
        try {
            toneGenerator.startTone(toneType, BEEP_MILLISECONDS);
        } catch (RuntimeException ignored) {
        }
    }
}
