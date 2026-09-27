# IDCheck

Student ID verification portal for CSCQC. Scan or type a student number,
the system checks the local database, shows the student's info, logs the
scan, and can email a notice to the student's Gmail.

Group 6 (BT-204), OOP finals project.

## How it looks

Same look as the school portal (cscqcph.com): campus photo background,
white card in the middle, green scan button, recent scans table below.

## Run it

```
pip install -r requirements.txt
python main.py            # opens the portal page (browser or window)
python main.py --cli      # terminal version, no browser needed
python main.py --demo     # load the sample students first
```

Or grab the exe from Releases. Double-click, wait a few seconds, the
portal opens. No Python needed.

## Loading your real student list

Click **Choose CSV** on the page (or `:import file.csv` in the terminal
version). The importer is not strict about headers:

| field | accepted headers |
|---|---|
| student_id | student_id, id, student_no, student number, lrn |
| full_name | full_name, name, student name |
| gmail | gmail, email, email address |
| course | course, program, strand |
| year_level | year_level, year, grade |
| section | section, sec, block |

Only the student number and name are required. Importing the same file
again updates records instead of duplicating them.
Example file: `data/students_sample.csv`.

## Email notices (optional)

Off by default so the system runs fully offline. To turn on:

1. Make a Gmail account for the project and enable 2-Step Verification.
2. Google Account > Security > App passwords > create one.
3. Put the address and app password in `idcheck/constants.py` and set
   `EMAIL_ENABLED = True`.

If the email fails (no internet, wrong password), the scan is still
saved and just marked "not sent". Scanning never breaks.

## Tests

```
python -m unittest discover -s tests -t . -v
```

## Rebuild the exe

```
pip install pyinstaller pillow
python tools/make_icon.py   # only if the icon needs regenerating
python tools/build.py       # -> dist/IDCheck.exe and dist/IDCheck/
```

Two builds come out. `IDCheck.exe` is one file, easiest to copy around.
`dist/IDCheck/` is the folder version, less likely to get flagged by
antivirus (PyInstaller's onefile bootloader is a known false positive).
Neither is code-signed, so SmartScreen may still ask once:
**More info > Run anyway**.

## Layout

```
IDCheck/
├── main.py               # entry point
├── idcheck/
│   ├── constants.py      # db path + gmail settings
│   ├── models.py         # Student, ScanRecord
│   ├── database.py       # sqlite, all SQL lives here
│   ├── importer.py       # CSV import with header aliases
│   ├── notifier.py       # Notifier ABC -> GmailNotifier / NullNotifier
│   ├── system.py         # IDCheckSystem facade
│   ├── web.py            # flask routes (/api/scan, /api/import, ...)
│   └── cli.py            # terminal scanner
├── templates/index.html  # portal page
├── static/               # css, js, school images
├── data/students_sample.csv
├── tests/
└── tools/                # icon generator + pyinstaller build script
```
