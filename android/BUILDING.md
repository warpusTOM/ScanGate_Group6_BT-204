# Building the APK

Read this if the build fails, or if you just want to know what the five steps
are doing. If it works, you don't need this file.

## The short version

```
python tools/build_apk.py
```

Output lands at `dist/ScanGate.apk`.

On Windows you can also double-click `build.bat`, which runs the same command
with `--test` in front and then keeps the window open so you can read the
output.

## What you need installed

| thing | version | why |
|---|---|---|
| JDK | 8 or newer, 17+ works fine | compiles the Java, signs the APK |
| Android SDK build-tools | 35.0.0 or newer | aapt2, zipalign, d8, apksigner |
| Android platform | android-34 | supplies `android.jar` |
| Python | 3.8+ | runs the build script |

The script expects the SDK at `C:\Users\Administrator\android-sdk` and the JDK
at `C:\Program Files\Java\jdk-26.0.1`. Both are set at the top of
`tools/build_apk.py`. Change those two lines if yours are somewhere else. It'll
tell you clearly if either is missing rather than failing halfway.

If the platform is missing:

```
sdkmanager "platforms;android-34" "build-tools;35.0.0"
```

## Why there is no Gradle

Gradle is the normal way to build an Android app. We didn't use it here for
three reasons.

First, this app has exactly one dependency, a single 600 KB jar, and Gradle
would spend a lot of time resolving a dependency graph to produce the same
0.6 MB APK.

Second, Gradle needs a background daemon running and a few hundred megabytes of
downloads before it'll do anything. The five build tools do the same job here
in about eight seconds.

Third, the tools aren't doing anything mysterious. Reading the build script top
to bottom tells you exactly how an APK gets made, which is more useful than a
build file that hides everything behind `implementation`.

The trade-off is real, though. If this project ever needs ten dependencies,
AndroidX, or Kotlin, move it to Gradle. Hand-rolling that would be silly.

## The five steps

### 1. Compile the resources

```
aapt2 compile --dir app/src/main/res -o build/res.zip
```

Turns the XML layouts and the PNG icon into Android's binary resource format.
Plain XML is too slow to parse on a phone at startup, so it gets converted
ahead of time.

### 2. Link the APK shell

```
aapt2 link -o build/base.apk -I android.jar \
    --manifest app/src/main/AndroidManifest.xml \
    --java build/gen -A app/src/main/assets \
    --min-sdk-version 24 --target-sdk-version 34 \
    --no-version-vectors build/res.zip
```

Builds an APK that has resources and a manifest but no code yet, and writes
`R.java`. That is the file that lets Java say `R.id.btnOpenCamera` instead of
a number.

Two details that cost us an hour each:

- `res.zip` goes in as a **plain argument at the end**. Using `-R res.zip`
  makes aapt2 treat it as an overlay and fail with `does not override an
  existing resource`, which sounds like a resource name clash and is not.
- `-A assets` is what puts `students.csv` inside the APK. Without it the app
  installs and then crashes on the first launch when it cannot find the
  roster.

### 3. Compile the Java

```
javac --release 8 -classpath android.jar;app/libs/zxing-core-3.5.3.jar \
    -d build/classes <every .java file, plus the generated R.java>
jar cf build/classes.jar -C build/classes .
```

`--release 8` is deliberate. Android accepts Java 8 bytecode everywhere from
API 24 up. `jar` needs the trailing `.` after `-C build/classes`, otherwise it
complains about parsing file arguments.

### 4. Make the dex

```
java -cp build-tools/35.0.0/lib/d8.jar com.android.tools.r8.D8 \
    --min-api 24 --lib android.jar --output build/dex \
    build/classes.jar app/libs/zxing-core-3.5.3.jar
```

This turns Java bytecode into dex, the format Android actually runs. Both jars
go in at once, which is how ZXing ends up inside the APK.

`d8` only ships as a `.bat` file, and `.bat` wrappers behave badly when called
from this shell, so the script calls its main class through `java` instead.
Same for `apksigner` in step 5.

Build-tools 34 will not work. Its copy of d8 crashes with a NullPointerException
while reading the `EnclosingMethod` attribute that a modern `javac` writes for
anonymous inner classes. Build-tools 35 fixed it. The script checks the version
and refuses to run on anything older.

### 5. Align, then sign

```
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks debug.keystore ... --out dist/ScanGate.apk build/aligned.apk
```

Android will not install an unsigned APK at all. The script generates
`debug.keystore` the first time and reuses it after that. The password is
`android`, which is the standard debug password and is not a secret, because
the key is not a real signing key.

Two things about folding `classes.dex` into the APK before aligning:

- The script copies each zip entry with `writestr(item, data)`. That keeps the
  entry's original compression method. Rebuilding the zip entry by entry with
  a hardcoded method would decompress `resources.arsc`, and an uncompressed,
  4-byte aligned `resources.arsc` is required for targetSdk 30 and above.
- `zipalign -p 4` is what makes that 4-byte alignment true. Signing after
  aligning, never the other way around.

## Verifying what came out

```
java -cp build-tools/35.0.0/lib/apksigner.jar \
    com.android.apksigner.ApkSignerTool verify --verbose dist/ScanGate.apk
```

You want to see `Verified using v2 scheme: true`. The v1 line saying `false` is
correct and expected when minSdk is 24 or higher, because v2 covers it. Only
worry if v2 is also false.

```
aapt2 dump badging dist/ScanGate.apk
```

Confirms the package name, the version, minSdk, targetSdk, and the permissions.
You should see `android.permission.CAMERA` and `android.permission.VIBRATE`,
and you should **not** see `android.permission.INTERNET`.

The build script prints the class table out of `classes.dex` as well, so you
can see every ScanGate class actually made it in.

## Messages that look like failures but are not

- `Note: Some input files use or override a deprecated API` from javac. We use
  `android.hardware.Camera` on purpose.
- `WARNING: A restricted method in java.lang.System has been called` from
  apksigner on a modern JDK. That is conscrypt loading a native library.
- `Verified using v1 scheme (JAR signing): false`. Explained above.

## Running the tests

```
python tools/build_apk.py --test
```

runs all three layers and stops the build if any of them fail. What each layer
is for is described at the top of its own file.

## Installing on a phone

Copy `dist/ScanGate.apk` across, tap it, allow install from unknown sources.
Android will warn about an unknown developer because the APK is signed with a
debug key. That is expected.

Over USB instead:

```
adb install -r dist/ScanGate.apk
```
