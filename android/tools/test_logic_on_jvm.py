"""Test layer 3 - compile the plain-Java classes and run them on the JVM.

Everything under com.scangate.app.logic, the model classes and the CSV reader
are written without a single Android import, so they can be compiled against
the JDK and run on the build machine. That is where the barcode decode path
gets tested for real: the harness builds an actual Code 128 and QR code in
memory, paints them into a camera-shaped brightness plane, and reads them back
with the app's own decoder.

This file also guards the layering. If somebody adds an `import android.` to
one of the logic classes, the compile below breaks and says why - which is the
point, because that import is what would stop the class being testable.

    python tools/test_logic_on_jvm.py
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "scangate" / "app"
LIBS_DIR = ROOT / "app" / "libs"
ROSTER = ROOT / "app" / "src" / "main" / "assets" / "students.csv"
HARNESS = ROOT / "tools" / "tests" / "ScanGateLogicTests.java"
TEST_CLASSES = ROOT / "build" / "test-classes"

JDK = Path(r"C:\Program Files\Java\jdk-26.0.1")
JAVAC = JDK / "bin" / "javac.exe"
JAVA = JDK / "bin" / "java.exe"

# Everything under these folders, plus the one data class, is supposed to be
# plain Java. Rather than keeping a list that goes stale, the whole folders are
# collected and every file is checked. Adding a new logic class means it gets
# tested without anyone remembering to register it.
PURE_FOLDERS = ["logic", "model"]
PURE_EXTRA_FILES = ["data/RosterCsvReader.java"]


def has_android_import(java_text: str) -> bool:
    for line in java_text.splitlines():
        if line.strip().startswith("import android."):
            return True
    return False


def collect_sources() -> list:
    """Returns the pure sources, refusing anything that has drifted into Android."""
    relatives = list(PURE_EXTRA_FILES)
    for folder in PURE_FOLDERS:
        for path in sorted((PACKAGE_DIR / folder).rglob("*.java")):
            relatives.append(path.relative_to(PACKAGE_DIR).as_posix())

    if not relatives:
        raise SystemExit("found no plain-Java sources to test")

    sources = []
    for relative in relatives:
        path = PACKAGE_DIR / relative
        if not path.exists():
            raise SystemExit(f"missing source file: {relative}")

        if has_android_import(path.read_text(encoding="utf-8")):
            raise SystemExit(
                f"{relative} now touches Android, so it cannot be tested off-device.\n"
                "Move the Android-specific part into a separate class and keep "
                "the logic here plain Java.")
        sources.append(str(path))
    return sources


def run_logic_tests() -> None:
    jars = sorted(str(path) for path in LIBS_DIR.glob("*.jar"))
    if not jars:
        raise SystemExit(f"no jars in {LIBS_DIR} - the decoder needs zxing-core")
    classpath = ";".join(jars)

    TEST_CLASSES.mkdir(parents=True, exist_ok=True)
    for stale in TEST_CLASSES.rglob("*.class"):
        stale.unlink()

    sources = collect_sources() + [str(HARNESS)]

    compile_command = [str(JAVAC), "--release", "8",
                       "-classpath", classpath,
                       "-d", str(TEST_CLASSES)] + sources
    print("+ compiling " + str(len(sources)) + " files for the JVM tests")
    subprocess.run(compile_command, check=True, cwd=str(ROOT))

    run_command = [str(JAVA), "-cp", str(TEST_CLASSES) + ";" + classpath,
                   "ScanGateLogicTests", str(ROSTER)]
    print("+ running ScanGateLogicTests\n")
    result = subprocess.run(run_command, cwd=str(ROOT))
    if result.returncode != 0:
        raise SystemExit("logic tests FAILED")


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    run_logic_tests()
