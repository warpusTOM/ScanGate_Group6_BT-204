package com.scangate.app.data;

import android.content.Context;

import com.scangate.app.model.Student;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Copies the bundled roster into the database the first time the app runs.
 *
 * The roster file lives at assets/students.csv inside the APK. On a fresh
 * install the students table is empty, so this loads the file and writes
 * every row. On every later launch the table already has rows and this does
 * nothing at all, which is why it is safe to call from onCreate every time.
 *
 * To ship a different roster, replace assets/students.csv and rebuild.
 */
public class RosterSeeder {

    public static final String ROSTER_ASSET = "students.csv";

    private final Context context;
    private final StudentDao studentDao;

    public RosterSeeder(Context context, StudentDao studentDao) {
        this.context = context.getApplicationContext();
        this.studentDao = studentDao;
    }

    /**
     * @return how many students were loaded, or 0 when the roster was already there
     */
    public int seedIfEmpty() throws IOException {
        if (studentDao.countAll() > 0) return 0;
        return loadFromAsset();
    }

    /** Force a reload from the bundled CSV, replacing whatever is in the table. */
    public int loadFromAsset() throws IOException {
        InputStream stream = context.getAssets().open(ROSTER_ASSET);
        try {
            List<Student> students = RosterCsvReader.read(stream);
            return studentDao.replaceAll(students);
        } finally {
            stream.close();
        }
    }
}
