package com.scangate.app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.scangate.app.R;
import com.scangate.app.logic.AttendanceStatus;
import com.scangate.app.logic.ClassClock;
import com.scangate.app.model.ScanLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Fills the "Recent scans" list.
 *
 * Each row shows the time in 12-hour form, the student number under it, and
 * a coloured chip on the right. A plain text list would be easier, but then
 * you cannot tell a late scan from an on-time one at a glance, which is the
 * one thing the list is actually for.
 */
public class ScanLogAdapter extends BaseAdapter {

    private static final float CHIP_CORNER_RADIUS = 24f;

    private final LayoutInflater inflater;
    private final Context context;
    private final List<ScanLog> logs = new ArrayList<ScanLog>();

    public ScanLogAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    /** Replaces the whole list. */
    public void setLogs(List<ScanLog> newLogs) {
        logs.clear();
        if (newLogs != null) logs.addAll(newLogs);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return logs.size();
    }

    @Override
    public ScanLog getItem(int position) {
        return logs.get(position);
    }

    @Override
    public long getItemId(int position) {
        return logs.get(position).rowId;
    }

    @Override
    public View getView(int position, View reused, ViewGroup parent) {
        View row = reused;
        if (row == null) {
            row = inflater.inflate(R.layout.row_scan_log, parent, false);
        }

        ScanLog log = logs.get(position);

        TextView timeText = row.findViewById(R.id.tvLogTime);
        TextView idText = row.findViewById(R.id.tvLogStudentId);
        TextView statusText = row.findViewById(R.id.tvLogStatus);

        timeText.setText(ClassClock.stampTo12Hour(log.timestamp));
        idText.setText(log.studentId);
        statusText.setText(log.status);

        int backgroundId;
        int textColor;
        if (AttendanceStatus.LATE.equals(log.status)) {
            backgroundId = R.color.status_late;
            textColor = android.graphics.Color.parseColor("#212529");
        } else if (AttendanceStatus.EARLY.equals(log.status)) {
            backgroundId = R.color.status_early;
            textColor = android.graphics.Color.WHITE;
        } else if (AttendanceStatus.NOT_REGISTERED.equals(log.status)) {
            backgroundId = R.color.status_unknown;
            textColor = android.graphics.Color.WHITE;
        } else {
            backgroundId = R.color.status_on_time;
            textColor = android.graphics.Color.WHITE;
        }

        GradientDrawable chip = new GradientDrawable();
        chip.setColor(context.getResources().getColor(backgroundId, context.getTheme()));
        chip.setCornerRadius(CHIP_CORNER_RADIUS);
        statusText.setBackground(chip);
        statusText.setTextColor(textColor);

        return row;
    }
}
