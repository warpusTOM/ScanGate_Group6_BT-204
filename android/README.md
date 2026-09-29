# ScanGate for Android

The phone version of ScanGate. Point the camera at the barcode on a student
ID, and it tells you who the student is and whether they're early, on time, or
late for class.

Everything runs on the phone. No internet, no account, no server.

Group 6, BT-204. College of St. Catherine of Quezon City.

---

## What it does

Three things.

1. **Reads a barcode with the camera.** Code 128, Code 39, Code 93, Codabar,
   ITF, EAN, UPC, QR codes and Data Matrix all work.
2. **Reads the printed number off the card.** No barcode needed. It finds the
   row of digits, works out each character, and corrects the result against the
   roster.
3. **Looks the number up and shows the result.** Name, ID, section, and the
   time note, plus a row in the scan history.

The camera tries the barcode and the printed number in turn, a few times a
second each, and the first one to find something wins. You do not have to tell
it which to use.

Worn-out card, no barcode, nothing printed? There is a text box on the home
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

The real identity is on an RFID chip inside the card. That is what the school's
own tap reader uses, and it is why tapping a card brings up a name. No phone
camera can see a chip, so that path is out of reach here.

What is left is the printing. If the card carries a Code 128 barcode, that
usually holds the number. If it does not, the app reads the number off the card
as characters, which is the thing that works on a card with nothing but ink.

To find out what a card actually holds, photograph it and run:

```
python tools/read_card_image.py photo.jpg
```

It prints the barcode if there is one, and what the printed number reader makes
of the rest. The picture is tried several ways (direct, upscaled, contrast
stretched, inverted, cropped to the ink) because a phone photo of paper is a lot
worse than a live camera frame.

## Reading the printed number

This is the harder of the two paths, and it is worth being honest about how it
works and how well.

The reader is four steps. It decides which pixels are ink by comparing each one
against its own neighbourhood, so a shadow across the card does not matter. It
groups the ink into separate lumps, one per printed character. It squashes each
lump into a 12 by 18 grid and finds the closest shape in a library of 100 digit
shapes. Then it pulls the runs of digits out of the line and throws the rest
away, so a label like `STUDENT NO.` in front of the number is ignored.

Then the roster does the rest. The app knows all 262 valid student numbers, so a
single misread digit does not lose the student: the wrong reading is still much
closer to the right number than to any other one in the roster. If the answer is
not clear it refuses to guess, and when it does correct something the result
card says `(read as ...)` so nobody is looking at a hidden guess.

Measured accuracy, from `tools/test_ocr_on_jvm.py`:

```
46 synthetic cards, real fonts, blur, noise, uneven light, 2 degrees of tilt
exact reads      97.8%
student resolved 97.8%
```

The one case it misses is 30px print under heavy blur and noise with a lamp on
one side, where the digits come out about twenty pixels tall. On a card held at
15 cm the digits are more than twice that.

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
python tools/test_ocr_on_jvm.py    # measures the printed number reader
```

Run the third one at minimum. It builds an actual Code 128 and an actual QR code
in memory with the ZXing encoder, paints them into the same kind of brightness
frame a camera produces, and reads them back with the app's own decoder. So the
scan path is tested for real, not just compiled.

The fourth one is the only honest way to check a character reader. It draws 46
synthetic ID numbers in real fonts, blurs them, adds sensor noise, lights one
side of the card and tilts it, then runs the reader and counts. It fails the
build if accuracy drops.

`python tools/build_apk.py --test` runs all four before building.

## Which files do what

Every file is listed in [ARCHITECTURE.md](ARCHITECTURE.md).

Short version:

```
app/src/main/java/com/scangate/app/
    MainActivity.java          home screen
    CameraScanActivity.java    camera screen, both reading paths
    camera/                    opening the camera, beeps and buzzes
    logic/                     time maths, barcode decoding, roster matching
    logic/ocr/                 the printed number reader
    data/                      database and roster loading
    model/                     plain data classes
    ui/                        the result card and the history list
```

Everything under `logic/` and `model/`, plus `data/RosterCsvReader.java`, has no
Android imports at all. That's on purpose. It means all of it compiles and runs
on a laptop, which is how the barcode and OCR tests above work. Add an Android
import to one of them and the test runner refuses to run and tells you why.

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
