package com.scangate.app.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.scangate.app.R;
import com.scangate.app.logic.AttendanceStatus;
import com.scangate.app.model.Student;

/**
 * The white card that shows the result of a scan.
 *
 * All the findViewById calls and colour choices for the card live here, so
 * the screens only have to say "show this student" or "show not registered".
 */
public class ResultCard {

    private static final float CHIP_CORNER_RADIUS = 48f;

    private final Activity activity;
    private final LinearLayout card;
    private final TextView nameText;
    private final TextView idText;
    private final TextView sectionText;
    private final TextView statusChip;

    public ResultCard(Activity activity) {
        this.activity = activity;
        this.card = activity.findViewById(R.id.resultCard);
        this.nameText = activity.findViewById(R.id.tvStudentName);
        this.idText = activity.findViewById(R.id.tvStudentId);
        this.sectionText = activity.findViewById(R.id.tvStudentSection);
        this.statusChip = activity.findViewById(R.id.tvStatus);
    }

    /** Shows a student who was found, with the EARLY / ON TIME / LATE note. */
    public void showStudent(Student student, String status) {
        card.setVisibility(View.VISIBLE);
        nameText.setText(student.fullName);
        idText.setText("ID: " + student.studentId);
        sectionText.setText("Section: " + student.sectionLabel());
        applyStatus(status);
    }

    /** Shows a number that was read correctly but is not in the roster. */
    public void showNotRegistered(String scannedNumber) {
        card.setVisibility(View.VISIBLE);
        nameText.setText(activity.getString(R.string.result_not_registered));
        idText.setText(activity.getString(R.string.result_no_such_number, scannedNumber));
        sectionText.setText("");
        statusChip.setText(R.string.result_check_id);
        chipColor(R.color.status_unknown, android.graphics.Color.WHITE);
    }

    /**
     * Shows that the code was read fine but has no student number in it.
     *
     * This is a different problem from a number that is simply not on the
     * roster, and it deserves its own message. Some school IDs carry a QR code
     * that points at the school's Facebook page, or at a website, with no
     * number anywhere in it. Telling the operator "check the ID" in that case
     * sends them looking for a mistake that is not there.
     */
    public void showCodeWithoutNumber(String decodedText) {
        card.setVisibility(View.VISIBLE);
        nameText.setText(activity.getString(R.string.result_no_number_title));
        idText.setText(activity.getString(R.string.result_code_says, decodedText));
        sectionText.setText("");
        statusChip.setText(R.string.result_no_number_chip);
        chipColor(R.color.status_unknown, android.graphics.Color.WHITE);
    }

    public void hide() {
        card.setVisibility(View.GONE);
    }

    private void applyStatus(String status) {
        statusChip.setText(status);

        if (AttendanceStatus.LATE.equals(status)) {
            chipColor(R.color.status_late, android.graphics.Color.parseColor("#212529"));
        } else if (AttendanceStatus.EARLY.equals(status)) {
            chipColor(R.color.status_early, android.graphics.Color.WHITE);
        } else {
            chipColor(R.color.status_on_time, android.graphics.Color.WHITE);
        }
    }

    /** Paints the pill behind the status text. */
    private void chipColor(int backgroundColorId, int textColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(activity.getResources().getColor(backgroundColorId, activity.getTheme()));
        background.setCornerRadius(CHIP_CORNER_RADIUS);
        statusChip.setBackground(background);
        statusChip.setTextColor(textColor);
    }
}
