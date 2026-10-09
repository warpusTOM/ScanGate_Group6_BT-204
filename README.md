# AttendGate

Student attendance verification portal for CSCQC. Enter a student number
and the system checks the local database, shows the student's full name,
ID number, and section, marks the entry EARLY, ON TIME, or LATE against
the class start time, and records everything.

Group 5 (BT-204), OOP finals project. Runs fully offline on one laptop.
No internet, and no hardware of any kind. Every input is text.

## Who sees what

- **Students** get only the verification page (`/`). Type a number, get
  verified / not registered plus the time note.
  No student list, no logs, no import button.
- **Admins** log in at `/login` and get the dashboard (`/admin`):
  CSV import, student list, recent check-ins, late/on-time counts,
  time window settings, and account management.

First run has no accounts, so `/login` redirects to a one-time setup
page. Create the first admin there (ours is `molino`). Add or reset
more accounts later from the admin page. Passwords are stored as
PBKDF2 hashes in the local database, never in the code.

## How it looks

Same look as the school portal (cscqcph.com): campus photo background,
white card in the middle, green verify button.

## Run it

```
pip install -r requirements.txt
python main.py            # opens the portal page (browser or window)
python main.py --cli      # terminal version, no browser needed
python main.py --demo     # load the sample students first
```

Or grab the exe from Releases. Double-click, wait a few seconds, the
portal opens. No Python needed.

## The time notes

Every check-in is compared to the class start time (set on the admin page,
default 08:00):

- **EARLY** - more than 15 minutes before start
- **ON TIME** - inside the window (15 min before to 10 min after)
- **LATE** - more than 10 minutes past start

The window is adjustable on `/admin` under "Time window".

## Loading your real student list

Admins click **Choose CSV** on the dashboard (or `:import file.csv` in
the terminal version). The school portal's credentials export works
as-is (`section,student_no,surname,first_name,access_key`): names are
joined, blank student numbers fall back to the access key, and course +
year are read out of the section.

Other header spellings also work:

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

## Tests

```
python -m unittest discover -s tests -t . -v
```

## Rebuild the exe

```
pip install pyinstaller pillow
python tools/make_icon.py   # only if the icon needs regenerating
python tools/build.py       # -> dist/ScanGate.exe and dist/ScanGate/
```

Run the exe from `dist/` only. The `build/` folder is PyInstaller's
scratch space and the exe there won't start.

Two builds come out. `ScanGate.exe` is one file, easiest to copy around.
`dist/ScanGate/` is the folder version, less likely to get flagged by
antivirus (PyInstaller's onefile bootloader is a known false positive).
Neither is code-signed, so SmartScreen may still ask once:
**More info > Run anyway**.

## Layout

```
├── main.py               # entry point
├── idcheck/
│   ├── constants.py      # db path + time window defaults
│   ├── models.py         # Student, ScanRecord
│   ├── database.py       # sqlite, all SQL lives here
│   ├── auth.py           # admin accounts (PBKDF2) + login decorator
│   ├── importer.py       # CSV import with header aliases
│   ├── system.py         # IDCheckSystem facade, time classification
│   ├── web.py            # flask routes: public verify + admin dashboard
│   └── cli.py            # terminal client
├── templates/            # index (public), login, setup, admin
├── static/               # css, js, school images
├── data/students_sample.csv
├── tests/
├── tools/                # icon generator + pyinstaller build script
└── android/              # phone app, not part of this system
```
