"""Build ScanGate.apk using the raw Android build tools. No Gradle.

Why no Gradle? This app has no outside dependencies except one small jar, so
Gradle would only add a 100 MB download and a background daemon to the job.
The five tools below do everything Gradle would do for a project this size,
and they are all already inside the Android SDK.

    python tools/build_apk.py             build and verify
    python tools/build_apk.py --test      run the three test layers first
    python tools/build_apk.py --release   also copy the apk next to the repo

The five steps, in order:

    1. aapt2 compile   res/ -> res.zip        (turn XML and images into binary)
    2. aapt2 link      -> base.apk            (build the apk shell, make R.java)
    3. javac + jar     -> classes.jar         (compile the Java, zip the classes)
    4. d8              -> classes.dex         (turn Java bytecode into Android's)
    5. zipalign + sign -> ScanGate.apk        (align, then sign with the debug key)

Step 4 is where the ZXing barcode jar gets merged in. d8 takes several jars at
once and produces one classes.dex, which is how a plain Java library ends up
inside an Android app.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
MAIN = APP / "src" / "main"
JAVA_SRC = MAIN / "java"
RES_DIR = MAIN / "res"
ASSETS_DIR = MAIN / "assets"
MANIFEST = MAIN / "AndroidManifest.xml"
LIBS_DIR = APP / "libs"

BUILD = ROOT / "build"
OUT = ROOT / "dist"
APK_NAME = "ScanGate.apk"

SDK = Path(r"C:\Users\Administrator\android-sdk")
BUILD_TOOLS = SDK / "build-tools" / "35.0.0"
PLATFORM = SDK / "platforms" / "android-34"
ANDROID_JAR = PLATFORM / "android.jar"

JDK = Path(r"C:\Program Files\Java\jdk-26.0.1")
JAVA = JDK / "bin" / "java.exe"
JAVAC = JDK / "bin" / "javac.exe"
JAR = JDK / "bin" / "jar.exe"
KEYTOOL = JDK / "bin" / "keytool.exe"

MIN_SDK = "24"
TARGET_SDK = "34"

KEYSTORE = ROOT / "debug.keystore"
KEY_ALIAS = "androiddebugkey"
KEY_PASSWORD = "android"


def run(command: list) -> None:
    """Runs one build step and stops the whole build if it fails."""
    printable = " ".join(str(part) for part in command)
    print("+ " + (printable if len(printable) < 160 else printable[:157] + "..."))
    subprocess.run([str(part) for part in command], check=True, cwd=str(ROOT))


def check_toolchain() -> None:
    """Fails early and with a useful message, instead of midway through."""
    if not ANDROID_JAR.exists():
        raise SystemExit(
            "android.jar is missing. Install the platform with:\n"
            f'  sdkmanager "platforms;android-{TARGET_SDK}"')
    if not BUILD_TOOLS.exists():
        raise SystemExit(f"build-tools not found at {BUILD_TOOLS}")

    major = int(BUILD_TOOLS.name.split(".")[0])
    if major < 35:
        raise SystemExit(
            f"build-tools {BUILD_TOOLS.name} is too old. d8 in build-tools 34 "
            "crashes on anonymous inner classes.\n"
            '  sdkmanager "build-tools;35.0.0"')

    for tool in ("d8.jar", "apksigner.jar"):
        if not (BUILD_TOOLS / "lib" / tool).exists():
            raise SystemExit(f"missing {tool} in {BUILD_TOOLS / 'lib'}")

    if not list(LIBS_DIR.glob("*.jar")):
        raise SystemExit(f"no library jars in {LIBS_DIR}. "
                         "zxing-core is needed to read barcodes.")


def step_1_compile_resources() -> None:
    run([BUILD_TOOLS / "aapt2.exe", "compile", "--dir", str(RES_DIR),
         "-o", str(BUILD / "res.zip")])


def step_2_link_apk_shell() -> None:
    """Builds base.apk and generates R.java.

    res.zip goes in as a plain argument at the end. Using -R instead would
    make aapt2 treat it as an overlay and fail with "does not override an
    existing resource".
    """
    run([BUILD_TOOLS / "aapt2.exe", "link",
         "-o", str(BUILD / "base.apk"),
         "-I", str(ANDROID_JAR),
         "--manifest", str(MANIFEST),
         "--java", str(BUILD / "gen"),
         "-A", str(ASSETS_DIR),
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--no-version-vectors",
         str(BUILD / "res.zip")])


def step_3_compile_java() -> None:
    sources = sorted(str(path) for path in JAVA_SRC.rglob("*.java"))
    sources += sorted(str(path) for path in (BUILD / "gen").rglob("*.java"))
    if not sources:
        raise SystemExit("no .java files found under " + str(JAVA_SRC))

    classpath = [str(ANDROID_JAR)]
    classpath += sorted(str(path) for path in LIBS_DIR.glob("*.jar"))

    run([JAVAC, "--release", "8",
         "-classpath", ";".join(classpath),
         "-d", str(BUILD / "classes")] + sources)

    # The trailing "." matters. "jar cf x.jar -C dir" alone fails with
    # "Error parsing file arguments".
    run([JAR, "cf", str(BUILD / "classes.jar"), "-C", str(BUILD / "classes"), "."])


def step_4_make_dex() -> None:
    """Java bytecode -> Android bytecode.

    d8 only ships as a .bat file, and .bat wrappers are unreliable from this
    shell, so its main class is called through java directly.
    """
    inputs = [str(BUILD / "classes.jar")]
    inputs += sorted(str(path) for path in LIBS_DIR.glob("*.jar"))

    run([JAVA, "-cp", str(BUILD_TOOLS / "lib" / "d8.jar"), "com.android.tools.r8.D8",
         "--min-api", MIN_SDK,
         "--lib", str(ANDROID_JAR),
         "--output", str(BUILD / "dex")] + inputs)


def step_5_pack_align_sign() -> Path:
    """Folds classes.dex into the apk shell, aligns it, and signs it."""
    unsigned = BUILD / "unsigned.apk"

    # Copying each entry with writestr keeps its original compression method.
    # resources.arsc must stay uncompressed and 4-byte aligned for targetSdk 30+.
    with zipfile.ZipFile(BUILD / "base.apk") as source, \
            zipfile.ZipFile(unsigned, "w") as target:
        for item in source.infolist():
            target.writestr(item, source.read(item.filename))
        target.writestr("classes.dex", (BUILD / "dex" / "classes.dex").read_bytes())

    if not KEYSTORE.exists():
        print("+ creating debug.keystore (one time only)")
        run([KEYTOOL, "-genkeypair", "-keystore", str(KEYSTORE),
             "-alias", KEY_ALIAS, "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000",
             "-storepass", KEY_PASSWORD, "-keypass", KEY_PASSWORD,
             "-dname", "CN=Android Debug,O=Android,C=US"])

    run([BUILD_TOOLS / "zipalign.exe", "-f", "-p", "4",
         str(unsigned), str(BUILD / "aligned.apk")])

    final = OUT / APK_NAME
    run([JAVA, "-cp", str(BUILD_TOOLS / "lib" / "apksigner.jar"),
         "com.android.apksigner.ApkSignerTool", "sign",
         "--ks", str(KEYSTORE),
         "--ks-pass", "pass:" + KEY_PASSWORD,
         "--key-pass", "pass:" + KEY_PASSWORD,
         "--ks-key-alias", KEY_ALIAS,
         "--out", str(final),
         str(BUILD / "aligned.apk")])
    return final


def verify(apk: Path) -> None:
    run([JAVA, "-cp", str(BUILD_TOOLS / "lib" / "apksigner.jar"),
         "com.android.apksigner.ApkSignerTool", "verify", "--verbose", str(apk)])
    run([BUILD_TOOLS / "aapt2.exe", "dump", "badging", str(apk)])
    report_dex_contents(apk)


def report_dex_contents(apk: Path) -> None:
    """Confirms our classes are in the dex and Android's are not bundled."""
    import re

    dex = zipfile.ZipFile(apk).read("classes.dex")
    our_classes = sorted(set(
        name.decode() for name in re.findall(rb"Lcom/scangate/app/[A-Za-z0-9_$/]+;", dex)))
    zxing_classes = len(set(re.findall(rb"Lcom/google/zxing/[A-Za-z0-9_$/]+;", dex)))

    print(f"\ndex: {len(our_classes)} ScanGate classes, "
          f"{zxing_classes} ZXing classes referenced")
    for name in our_classes:
        print("   ", name)


def prepare_build_dirs() -> None:
    for folder in (BUILD / "gen", BUILD / "classes", BUILD / "dex", OUT):
        if folder.exists():
            shutil.rmtree(folder, ignore_errors=True)
        folder.mkdir(parents=True, exist_ok=True)


def main() -> None:
    if "--test" in sys.argv:
        from test_logic_on_jvm import run_logic_tests
        from check_database import check_database
        from check_source import check_sources

        check_sources()
        check_database()
        run_logic_tests()

    check_toolchain()
    prepare_build_dirs()

    step_1_compile_resources()
    step_2_link_apk_shell()
    step_3_compile_java()
    step_4_make_dex()
    apk = step_5_pack_align_sign()
    verify(apk)

    size_mb = apk.stat().st_size / 1e6
    print(f"\nAPK -> {apk}   ({size_mb:.1f} MB)")

    if "--release" in sys.argv:
        release_dir = ROOT.parent / "dist"
        release_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(apk, release_dir / APK_NAME)
        print(f"copy -> {release_dir / APK_NAME}")


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    main()
