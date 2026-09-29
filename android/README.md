# ScanGate for Android

The phone version of ScanGate. Point the camera at the barcode on a student
ID, and it tells you who the student is and whether they're early, on time, or
late for class.

Everything runs on the phone. No internet, no account, no server.

Group 6, BT-204. College of St. Catherine of Quezon City.

---

## What it does

Two things, and that's it.

1. **Reads the barcode on a student ID with the camera.** Code 128, Code 39,
   Code 93, Codabar, ITF, EAN, UPC, QR codes and Data Matrix all work.
2. **Looks the number up and shows the result.** Name, ID, section, and the
   time note, plus a row in the scan history.

Worn-out barcode? Card with no barcode at all? There's a text box on the home
screen. Type the number, press verify, same result.

## What the screens look like

**Home.** Green button at the top opens the camera. Under it sits the typing
box. Below that, the result card (hidden until something's scanned), the class
time setting, a stats line, and the last 25 scans.

**Camera.** Live preview with a white box in the middle to aim with. Bottom
left is the light button for dark hallways, bottom right is cancel.

Aiming is loose on purpose. The decoder reads the whole frame, so a barcode
hanging a bit outside the box still gets read.

## How it looks

Same as the laptop portal, on purpose. The school banner fills the background
with a dark wash over it, the CSCQC seal sits in the header, and the buttons are
the same green the portal uses. Nothing was drawn for this app. The seal and the
banner are the two images the portal already has, cut into Android sizes by
`tools/make_brand_assets.py`.

Want to see it without installing anything?

```
python tools/make_screen_preview.py     # -> dist/home-screen-preview.png
```

## What is actually on a student ID

Worth knowing before wondering why a scan did not work.

The QR code printed on a CSCQC ID points at `facebook.com/CollegeofStCatherine`.
That is the whole payload. It is the same code on every card, it is printed by
the school for marketing, and it holds no student number, so no scanner can ever
identify a student from it.

Some cards also carry a Code 128 barcode. That one usually does hold the student
number, and it is the code this app is built for.

If a card has neither, use the typing box. That path works regardless.

To find out what a card actually holds, photograph it and run:

```
python tools/decode_image.py photo.jpg
```

It prints the format, the exact text, the length, the raw bytes and the error
correction level, and it tries the picture several ways (direct, upscaled,
contrast stretched, cropped to the code) because a phone photo of paper is a lot
worse than a live camera frame.

## Reading a card

Hold the phone about 15 cm away, fill the box with the barcode, hold still for
a second. One beep means it read something. The screen closes and the result
appears.

Not in the roster? You get a red card, a lower double beep, and a longer buzz.
Nothing gets added to the history in that case, matching what the laptop
portal does.

School ID barcodes aren't standardised, so the parser is deliberately greedy.
Some cards print the number in two places. Some glue a prefix to the front
(`STU20253152`). Some split it up (`2025-3152`). The parser builds a short list
of possible numbers and tries each one against the roster, so those cards still
work.

## The time notes

Every scan gets compared to the class start time. Default is 08:00:

| note | when |
|---|---|
| EARLY | more than 15 minutes before class |
| ON TIME | 15 minutes before, up to 10 minutes after |
| LATE | more than 10 minutes after |

Change the start time and both window sizes from the **Class time** link on the
home screen. It saves to the phone's database and survives a restart.

One trap if you're editing the code. Timestamps stored in the database always
stay in 24-hour form (`2026-09-27 20:27:28`), and only the on-screen copy gets
turned into 12-hour time. The "how many scans today" counter slices the date
out of that string. Store `8:27:28 PM` and the date disappears, so every day's
count breaks.

## Where the student list comes from

The roster ships inside the APK at `app/src/main/assets/students.csv`. First
run copies it into the phone's database, and after that the file gets ignored.
To ship a different list, replace that file and rebuild.

262 students are in there now, BT-104 up to BT-303.

Column names are flexible, because the file normally comes from the registrar
and nobody wants to hand-edit it. `Student No`, `ID`, `LRN`, `Student Number`
all mean the same thing. Same for names, courses, years and sections. The
school portal's own credentials export works untouched, name split into
`first_name` and `surname`, `access_key` used as the number when `student_no`
is blank.

## Install it

Grab `ScanGate.apk` from the release, or build it yourself (below). Copy it to
the phone, tap it, allow install from unknown sources.

Android will warn about an unknown developer the first time. That's because
the APK is signed with a debug key, not a Play Store key. Tap through it.

Android 7.0 is the minimum. Tested target is Android 14.

## Build it

You'll need the Android SDK (build-tools 35 or newer, platform 34) and a JDK.

```
python tools/build_apk.py
```

That's the whole thing. It runs the five build tools in order and leaves the
APK in `dist/ScanGate.apk`. There's a `build.bat` next to it if you'd rather
double-click.

Add `--test` to run the three test layers first:

```
python tools/build_apk.py --test
```

More detail, including why there's no Gradle, in [BUILDING.md](BUILDING.md).

## Tests

```
python tools/check_source.py       # catches views looked up on the wrong screen
python tools/check_database.py     # runs every query against a real SQLite
python tools/test_logic_on_jvm.py  # 82 checks, including real barcodes
```

Run the last one at minimum. It builds an actual Code 128 and an actual QR code
in memory with the ZXing encoder, paints them into the same kind of brightness
frame a camera produces, and reads them back with the app's own decoder. So the
scan path is tested for real, not just compiled.

`python tools/build_apk.py --test` runs all three before building.

## Which files do what

Every file is listed in [ARCHITECTURE.md](ARCHITECTURE.md).

Short version:

```
app/src/main/java/com/scangate/app/
    MainActivity.java          home screen
    CameraScanActivity.java    camera screen
    camera/                    opening the camera, beeps and buzzes
    logic/                     the time maths, the number parser, the decoder
    data/                      database and roster loading
    model/                     plain data classes
    ui/                        the result card and the history list
```

Classes in `logic/` and `model/`, plus `data/RosterCsvReader.java`, have no
Android imports at all. That's on purpose. It means they compile and run on a
laptop, which is how the barcode test above works. Add an Android import to one
of them and the test runner refuses to run and tells you why.

## The barcode library

[ZXing](https://github.com/zxing/zxing) core 3.5.3, Apache 2.0. One jar, about
600 KB, sitting in `app/libs/`. Plain Java, no Android parts, which is why the
APK only comes to 0.6 MB.

Writing our own barcode reader was never on the table. ZXing has been the
standard open-source reader for about twenty years, and every phone scanner
you've used is probably built on it.

## Privacy

No `INTERNET` permission is requested. That's deliberate, not an oversight. The
app can't send anything anywhere even if it wanted to. Roster, scan logs and
class time all live in the app's private database file on the phone.

## Known limits

- We use `android.hardware.Camera`, the old camera API. It's marked deprecated
  but still works on everything from Android 7 up, and it needs no extra
  libraries. Camera2 would be more code for no gain here.
- Glossy ID cards bounce the torch straight back and get hard to read. Tilt the
  card slightly, or switch the light off and use room lighting.
- Only students already in the roster get logged. There's no way to add one
  from the phone yet, you have to change the CSV and rebuild.
