package com.scangate.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // class window: starts 08:00, on time from 07:45 to 08:10
    private static final int START_H = 8;
    private static final int START_M = 0;
    private static final int EARLY_BEFORE = 15;
    private static final int LATE_AFTER = 10;

    private Db db;
    private EditText input;
    private LinearLayout resultCard;
    private TextView tvName, tvId, tvSection, tvStatus, tvStats;
    private ListView lvLogs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new Db(this);
        try {
            db.seedIfEmpty(this);
        } catch (Exception e) {
            throw new RuntimeException("could not load student roster", e);
        }

        input = findViewById(R.id.inputId);
        resultCard = findViewById(R.id.resultCard);
        tvName = findViewById(R.id.tvName);
        tvId = findViewById(R.id.tvId);
        tvSection = findViewById(R.id.tvSection);
        tvStatus = findViewById(R.id.tvStatus);
        tvStats = findViewById(R.id.tvStats);
        lvLogs = findViewById(R.id.lvLogs);

        Button btn = findViewById(R.id.btnScan);
        btn.setOnClickListener(v -> scan());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                scan();
                return true;
            }
            return false;
        });

        refreshLogs();
        refreshStats();
    }

    private void scan() {
        String id = input.getText().toString().trim();
        if (id.isEmpty()) return;

        Student s = db.findStudent(id);
        Calendar now = Calendar.getInstance();
        if (s == null) {
            showNotRegistered(id);
        } else {
            String status = TimeNote.classify(now, START_H, START_M,
                    EARLY_BEFORE, LATE_AFTER);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.US).format(now.getTime());
            db.logScan(s.id, ts, status, "");
            showResult(s, status);
        }
        input.setText("");
        refreshLogs();
        refreshStats();
    }

    private void showResult(Student s, String status) {
        resultCard.setVisibility(View.VISIBLE);
        tvName.setText(s.name);
        tvId.setText("ID: " + s.id);
        tvSection.setText("Section: " + s.section);
        paintStatus(status);
    }

    private void showNotRegistered(String id) {
        resultCard.setVisibility(View.VISIBLE);
        tvName.setText("NOT REGISTERED");
        tvId.setText("No student with number " + id);
        tvSection.setText("");
        tvStatus.setText("CHECK THE ID");
        badge(Color.parseColor("#dc3545"), Color.WHITE);
    }

    private void paintStatus(String status) {
        tvStatus.setText(status);
        int bg;
        int fg = Color.WHITE;
        if (TimeNote.LATE.equals(status)) {
            bg = Color.parseColor("#ffc107");
            fg = Color.parseColor("#212529");
        } else if (TimeNote.EARLY.equals(status)) {
            bg = Color.parseColor("#0d6efd");
        } else {
            bg = Color.parseColor("#198754");
        }
        badge(bg, fg);
    }

    private void badge(int bgColor, int fgColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(48f);
        tvStatus.setBackground(g);
        tvStatus.setTextColor(fgColor);
    }

    private void refreshLogs() {
        List<ScanLog> logs = db.recentScans(20);
        List<String> rows = new ArrayList<>();
        for (ScanLog l : logs) {
            String time = l.timestamp.length() >= 19
                    ? l.timestamp.substring(11) : l.timestamp;
            rows.add(time + "   " + l.studentId + "   " + l.status);
        }
        lvLogs.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, rows));
    }

    private void refreshStats() {
        String today = new SimpleDateFormat("yyyy-MM-dd",
                Locale.US).format(Calendar.getInstance().getTime());
        int scans = db.countScansToday(today, "");
        int late = db.countScansToday(today, TimeNote.LATE);
        tvStats.setText(db.countStudents() + " students  |  today: "
                + scans + " scans (" + late + " late)");
    }
}
