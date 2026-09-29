# What every file does

A map of the project. If you are looking for where something happens, start
here.

## The layout

```
android/
├── README.md                  what the app is and how to use it
├── BUILDING.md                how the APK gets built, step by step
├── ARCHITECTURE.md            this file
├── build.bat                  double-click build for Windows
├── app/
│   ├── libs/
│   │   └── zxing-core-3.5.3.jar        the barcode reader library
│   └── src/main/
│       ├── AndroidManifest.xml         app name, version, screens, permissions
│       ├── assets/
│       │   └── students.csv            the roster, 262 students
│       ├── java/com/scangate/app/      all the code
│       └── res/
│           ├── drawable/               backgrounds, buttons, aiming box, icon
│           ├── layout/                 the four screens and popups
│           └── values/
│               ├── strings.xml         every piece of text the user can read
│               └── colors.xml          the palette
└── tools/
    ├── build_apk.py           builds the APK
    ├── check_source.py        test layer 1
    ├── check_database.py      test layer 2
    ├── test_logic_on_jvm.py   test layer 3
    └── tests/
        └── ScanGateLogicTests.java     the actual test cases
```

## The code, folder by folder

The package is split by what a class is responsible for, not by screen. So the
barcode decoder does not live next to the camera screen, it lives with the rest
of the logic.

### Top level

| file | what it does |
|---|---|
| `MainActivity.java` | The home screen. Owns the buttons, the typing box, the result card and the history list. Decides what happens when a number arrives. |
| `CameraScanActivity.java` | The camera screen. Opens the preview, throws most frames away, runs the decoder on a background thread, and hands the raw barcode text back. |
| `BuildConfig.java` | The version constants Gradle would normally generate. Written by hand because there is no Gradle. |

### `camera/`

| file | what it does |
|---|---|
| `CameraController.java` | Wraps `android.hardware.Camera`. Picks a preview size near 1280x720, sets continuous autofocus, works out the display rotation, and pushes frames to a listener. Also owns the torch. |
| `ScanFeedback.java` | The beep and the buzz. Uses `ToneGenerator`, so there is no sound file in the project. |

### `logic/`

This whole folder is plain Java with no Android in it. That is what makes the
barcode decoder testable on a laptop.

| file | what it does |
|---|---|
| `AttendanceStatus.java` | The four status strings. They go into the database, so they match the laptop portal's spelling. |
| `ClassClock.java` | The time maths. Decides EARLY / ON TIME / LATE, builds the 24-hour timestamp that gets stored, and converts times to 12-hour for display. |
| `StudentNumberParser.java` | Pulls candidate student numbers out of whatever the barcode said. Handles JSON payloads, URLs with a query string, prefixes, separators, and letter-only codes. |
| `BarcodeDecoder.java` | The ZXing wrapper. Takes one camera frame in NV21 form and returns the text inside the barcode, or null. |

### `data/`

| file | what it does |
|---|---|
| `ScanGateDatabase.java` | The only place that creates tables. Also holds the three `CREATE` statements as constants, so the database test can read them straight out of this file. |
| `StudentDao.java` | Reads and writes the `students` table. |
| `ScanLogDao.java` | Reads and writes the `scan_logs` table. |
| `SettingsDao.java` | Reads and writes the `settings` table, with a fallback to defaults for anything missing or corrupt. |
| `RosterCsvReader.java` | Turns CSV text into `Student` objects. Handles all the header spellings. Plain Java, no Android. |
| `RosterSeeder.java` | Opens `assets/students.csv` and fills the students table on first run. Does nothing on later runs. |

### `model/`

| file | what it does |
|---|---|
| `Student.java` | One roster row. |
| `ScanLog.java` | One scan history row. |
| `ClassTimeSettings.java` | Class start time plus the two window sizes, with defaults and a parser for `HH:MM` text. |

### `ui/`

| file | what it does |
|---|---|
| `ResultCard.java` | The white result card. Knows which colour each status gets. |
| `ScanLogAdapter.java` | Fills the history list, one row per scan, with the coloured status chip. |

### `res/`

| file | what it does |
|---|---|
| `layout/activity_main.xml` | The home screen. |
| `layout/activity_camera_scan.xml` | The camera screen: preview, aiming box, torch, cancel. |
| `layout/row_scan_log.xml` | One row of the history list. |
| `layout/dialog_class_time.xml` | The class time popup. |
| `drawable/scan_frame.xml` | The white aiming box drawn over the preview. |
| `drawable/bg_button_green.xml` | The main green button. |
| `drawable/bg_button_outline.xml` | The outline buttons. |
| `drawable/bg_input.xml` | The white box behind the typing field. |
| `drawable/card.xml` | The result card background. |
| `drawable/ic_launcher.png` | The app icon. |
| `values/strings.xml` | All user-facing text. |
| `values/colors.xml` | All the colours. |

## How a scan actually flows

Worth reading once, because it explains why the classes are split the way they
are.

```
1.  User taps the green button
      MainActivity.openCameraScreen()
        -> starts CameraScanActivity

2.  CameraScanActivity asks for the CAMERA permission, then shows the preview
      CameraController.start()
        -> picks a preview size, starts pushing frames

3.  About 30 frames a second arrive. Most are dropped on purpose.
    Five a second get copied and handed to the decoder
      CameraScanActivity.onFrame()

4.  On a background thread, the decoder looks for a barcode
      BarcodeDecoder.decode(nv21Frame, width, height)
        -> ZXing tries Code 128, Code 39, QR, and the rest
        -> returns the text, or null if this frame had nothing

5.  A frame with a barcode ends the screen
      beep + buzz, then setResult(EXTRA_SCANNED_TEXT) and finish()

6.  Back on the home screen with the raw text
      MainActivity.handleScannedText(rawText)
        -> StudentNumberParser.candidates(rawText)     a short list, best first
        -> StudentDao.findById(candidate)              try each against the roster
        -> first match wins

7.  Found:
      ClassClock.classify(now, classTime)              EARLY / ON TIME / LATE
      ScanLogDao.insert(...)                           write the history row
      ResultCard.showStudent(...)                      show the card
    Not found:
      ResultCard.showNotRegistered(...)                red card, low beep

8.  MainActivity.refreshScreen() updates the stats line and the history list
```

The database is opened once, in `MainActivity.onCreate`, and closed in
`onDestroy`. The camera screen never touches it. That is why the lookup lives
on the home screen instead of over the preview.

## The database

Three tables, in the app's private file `scangate.db`.

```
students
    student_id   TEXT PRIMARY KEY
    full_name    TEXT NOT NULL
    gmail        TEXT NOT NULL DEFAULT ''
    course       TEXT NOT NULL DEFAULT ''
    year_level   TEXT NOT NULL DEFAULT ''
    section      TEXT NOT NULL DEFAULT ''

scan_logs
    id           INTEGER PRIMARY KEY AUTOINCREMENT
    student_id   TEXT NOT NULL
    timestamp    TEXT NOT NULL        always "YYYY-MM-DD HH:MM:SS", 24-hour
    note         TEXT NOT NULL DEFAULT ''
    status       TEXT NOT NULL DEFAULT ''

settings
    key          TEXT PRIMARY KEY     start_time, early_before, late_after
    value        TEXT NOT NULL
```

Column names match the laptop portal's database on purpose. A roster or a log
file can be moved between the two without conversion.

`SCHEMA_VERSION` is 2. Version 1 shipped without the `settings` table, so
`onUpgrade` adds it. Bump the number whenever a `CREATE` statement changes.

## Testing

Three layers, each catching something the others cannot.

| layer | file | catches |
|---|---|---|
| 1. source contract | `tools/check_source.py` | A view looked up on the wrong screen. That compiles fine and then throws a NullPointerException on the phone. |
| 2. database | `tools/check_database.py` | A misspelled column, a query that only breaks once there is a row, a date filter that silently matches nothing. Runs the real SQL against a real SQLite. |
| 3. logic on the JVM | `tools/test_logic_on_jvm.py` | The time maths, the number parser, the CSV import, and the barcode decode path. 80 checks. |

Layer 3 builds a real Code 128 and a real QR code in memory, paints them into
the same kind of brightness plane a camera frame produces, and reads them back
with the app's own decoder. So the scan path is verified without needing a
phone or an emulator.

`python tools/build_apk.py --test` runs all three before building.

## Adding a screen or a class

- New screen: add the Java file, add an `<activity>` entry to the manifest, add
  a layout, then run `python tools/check_source.py` to confirm every view it
  looks up really is in its own layout.
- New table or column: change the `CREATE` constant, bump `SCHEMA_VERSION`, add
  the step to `onUpgrade`, and update `EXPECTED_TABLES` or the query list in
  `tools/check_database.py` so the change is actually tested.
- New logic: if it does not need Android, put it in `logic/` and add it to
  `PURE_SOURCE_FILES` in `tools/test_logic_on_jvm.py`. Then it gets tested for
  free.
