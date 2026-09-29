package com.scangate.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.scangate.app.camera.ScanFeedback;
import com.scangate.app.data.RosterSeeder;
import com.scangate.app.data.ScanGateDatabase;
import com.scangate.app.data.ScanLogDao;
import com.scangate.app.data.SettingsDao;
import com.scangate.app.data.StudentDao;
import com.scangate.app.logic.AttendanceStatus;
import com.scangate.app.logic.ClassClock;
import com.scangate.app.logic.StudentNumberParser;
import com.scangate.app.logic.StudentRosterMatcher;
import com.scangate.app.model.ClassTimeSettings;
import com.scangate.app.model.Student;
import com.scangate.app.ui.ResultCard;
import com.scangate.app.ui.ScanLogAdapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The home screen.
 *
 * Two ways in:
 *   the green button opens the camera and reads the barcode on the ID
 *   the text box underneath is the backup for a card whose barcode is worn out
 *
 * Either way the number ends up in handleScannedText(), which does the actual
 * work: look the number up in the roster, work out the time note, write the
 * log row, and show the card.
 *
 * Only students who are actually in the roster get logged. A number that is
 * not registered shows the red card and makes a low tone, but it does not go
 * into the history, matching what the laptop portal does.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_CAMERA_SCAN = 201;
    private static final int RECENT_SCAN_LIMIT = 25;

    private ScanGateDatabase database;
    private StudentDao studentDao;
    private ScanLogDao scanLogDao;
    private SettingsDao settingsDao;

    private EditText inputField;
    private TextView statsText;
    private ScanLogAdapter logAdapter;
    private ResultCard resultCard;
    private ScanFeedback feedback;

    /** The class time currently in effect, reloaded every time the screen wakes. */
    private ClassTimeSettings classTime;

    /**
     * Every student number in the roster, held in memory.
     *
     * A printed number read off a card can come back one digit wrong. This list
     * is what turns that into a corrected answer instead of a wrong one. It is
     * read once when the screen starts rather than on every scan, because 262
     * short strings is nothing to hold and a database round trip per scan is
     * not worth paying.
     */
    private List<String> knownNumbers = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        openDatabase();
        wireViews();
    }

    private void openDatabase() {
        database = new ScanGateDatabase(this);
        studentDao = new StudentDao(database);
        scanLogDao = new ScanLogDao(database);
        settingsDao = new SettingsDao(database);

        try {
            new RosterSeeder(this, studentDao).seedIfEmpty();
        } catch (IOException rosterProblem) {
            Toast.makeText(this, "Roster could not be loaded: "
                    + rosterProblem.getMessage(), Toast.LENGTH_LONG).show();
        }

        classTime = settingsDao.load();
        knownNumbers = studentDao.findAllIds();
    }

    private void wireViews() {
        inputField = findViewById(R.id.inputStudentNumber);
        statsText = findViewById(R.id.tvStats);

        resultCard = new ResultCard(this);
        feedback = new ScanFeedback();

        ListView logList = findViewById(R.id.lvScanLogs);
        logAdapter = new ScanLogAdapter(this);
        logList.setAdapter(logAdapter);

        TextView cameraButton = findViewById(R.id.btnOpenCamera);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openCameraScreen();
            }
        });

        TextView verifyButton = findViewById(R.id.btnVerifyTyped);
        verifyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                verifyTypedNumber();
            }
        });

        // The "done" key on the keyboard should verify, same as the button.
        inputField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    verifyTypedNumber();
                    return true;
                }
                return false;
            }
        });

        TextView classTimeButton = findViewById(R.id.btnClassTime);
        classTimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showClassTimeDialog();
            }
        });

        refreshScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        classTime = settingsDao.load();
        refreshScreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        feedback.release();
        if (database != null) database.close();
    }

    // ------------------------------------------------------------------
    // the two ways in
    // ------------------------------------------------------------------

    private void openCameraScreen() {
        startActivityForResult(new Intent(this, CameraScanActivity.class),
                REQUEST_CAMERA_SCAN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAMERA_SCAN) return;
        if (resultCode != RESULT_OK || data == null) return;

        String text = data.getStringExtra(CameraScanActivity.EXTRA_SCANNED_TEXT);
        String source = data.getStringExtra(CameraScanActivity.EXTRA_SCANNED_SOURCE);

        if (CameraScanActivity.SOURCE_PRINTED.equals(source)) {
            handlePrintedNumber(text,
                    data.getStringArrayExtra(CameraScanActivity.EXTRA_SCANNED_ALTERNATIVES));
        } else {
            handleScannedText(text);
        }
    }

    private void verifyTypedNumber() {
        String typed = inputField.getText().toString().trim();
        if (typed.isEmpty()) return;

        handleScannedText(typed);
        inputField.setText("");
    }

    // ------------------------------------------------------------------
    // the actual work
    // ------------------------------------------------------------------

    /**
     * Takes whatever text came out of the barcode or the text box and tries to
     * turn it into a student.
     *
     * The parser returns several candidates because ID barcodes are not
     * standardised. Each one is looked up in turn, and the first that matches a
     * real student wins. If none of them match, the best guess is shown on the
     * red card so the operator can see what was actually read.
     */
    private void handleScannedText(String rawText) {
        List<String> candidates = StudentNumberParser.candidates(rawText);

        // Nothing number-shaped in the code at all. That is a different problem
        // from a number that is not on the roster, so it gets its own card.
        if (candidates.isEmpty()) {
            String decoded = shorten(rawText, 48);
            if (!decoded.isEmpty()) showCodeWithoutNumber(decoded);
            return;
        }

        for (int i = 0; i < candidates.size(); i++) {
            Student student = studentDao.findById(candidates.get(i));
            if (student != null) {
                recordScan(student);
                return;
            }
        }

        showNotRegistered(candidates.get(0));
    }

    /** Keeps a long barcode payload from stretching the card off the screen. */
    private static String shorten(String text, int limit) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "...";
    }

    /** A real student: work out the note, save the log row, show the card. */
    private void recordScan(Student student) {
        recordScan(student, "");
    }

    private void recordScan(Student student, String note) {
        Calendar now = Calendar.getInstance();
        String status = ClassClock.classify(now, classTime);
        scanLogDao.insert(student.studentId, ClassClock.storageStamp(now), status, "");

        resultCard.showStudent(student, status, note);
        refreshScreen();
    }

    // ------------------------------------------------------------------
    // the printed number path
    // ------------------------------------------------------------------

    /**
     * A number read off the card as characters, rather than out of a barcode.
     *
     * The reader is good but not perfect, so this runs in two passes.
     *
     * First it tries every candidate exactly as read. A clean read lands here
     * and costs nothing.
     *
     * Then it falls back to the roster. Since the app knows all 262 valid
     * student numbers, a single misread digit can still be resolved: the wrong
     * reading is far closer to the right number than to any other one. The
     * matcher refuses when the answer is not clear, so this can never pick
     * between two students, and when it does correct something the card says so
     * instead of hiding the guess.
     */
    private void handlePrintedNumber(String best, String[] alternatives) {
        List<String> candidates = new ArrayList<String>();
        if (best != null && !best.isEmpty()) candidates.add(best);
        if (alternatives != null) {
            for (int i = 0; i < alternatives.length; i++) {
                if (alternatives[i] != null && !alternatives[i].isEmpty()) {
                    candidates.add(alternatives[i]);
                }
            }
        }
        if (candidates.isEmpty()) return;

        for (int i = 0; i < candidates.size(); i++) {
            Student student = studentDao.findById(candidates.get(i));
            if (student != null) {
                recordScan(student);
                return;
            }
        }

        for (int i = 0; i < candidates.size(); i++) {
            StudentRosterMatcher.Match match =
                    StudentRosterMatcher.match(candidates.get(i), knownNumbers);
            if (match == null) continue;

            Student student = studentDao.findById(match.studentId);
            if (student == null) continue;

            recordScan(student, getString(R.string.result_read_as, match.readText));
            return;
        }

        showNotRegistered(candidates.get(0));
    }

    /** A number that was read fine but is not in the roster. */
    private void showNotRegistered(String scannedNumber) {
        resultCard.showNotRegistered(scannedNumber);
        feedback.playNotFound();
        feedback.vibrate(this, 120L);
        refreshScreen();
    }

    /** The camera read a code, but there is no student number inside it. */
    private void showCodeWithoutNumber(String decodedText) {
        resultCard.showCodeWithoutNumber(decodedText);
        feedback.playNotFound();
        feedback.vibrate(this, 120L);
        refreshScreen();
    }

    private void refreshScreen() {
        int studentCount = studentDao.countAll();
        int scansToday = scanLogDao.countToday("");
        int lateToday = scanLogDao.countToday(AttendanceStatus.LATE);

        statsText.setText(studentCount + " students  |  today: " + scansToday
                + " scans (" + lateToday + " late)  |  class "
                + classTime.startTimeText());

        logAdapter.setLogs(scanLogDao.findRecent(RECENT_SCAN_LIMIT));
    }

    // ------------------------------------------------------------------
    // class time
    // ------------------------------------------------------------------

    private void showClassTimeDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_class_time, null);
        final EditText startField = content.findViewById(R.id.dialogStartTime);
        final EditText earlyField = content.findViewById(R.id.dialogEarlyBefore);
        final EditText lateField = content.findViewById(R.id.dialogLateAfter);

        startField.setText(classTime.startTimeText());
        earlyField.setText(String.valueOf(classTime.earlyBeforeMinutes));
        lateField.setText(String.valueOf(classTime.lateAfterMinutes));

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_class_time_title)
                .setView(content)
                .setPositiveButton(R.string.dialog_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveClassTime(startField, earlyField, lateField);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void saveClassTime(EditText startField, EditText earlyField, EditText lateField) {
        int[] clock = ClassTimeSettings.parseClock(startField.getText().toString());
        if (clock == null) {
            Toast.makeText(this, R.string.dialog_bad_time, Toast.LENGTH_LONG).show();
            return;
        }

        classTime = new ClassTimeSettings(
                clock[0],
                clock[1],
                readNumber(earlyField, ClassTimeSettings.DEFAULT_EARLY_BEFORE_MINUTES),
                readNumber(lateField, ClassTimeSettings.DEFAULT_LATE_AFTER_MINUTES));

        settingsDao.save(classTime);
        Toast.makeText(this, getString(R.string.class_time_saved, classTime.startTimeText()),
                Toast.LENGTH_SHORT).show();
        refreshScreen();
    }

    private static int readNumber(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException blankOrJunk) {
            return fallback;
        }
    }
}
