"""Test layer 4 - measure the printed number reader.

A character reader cannot be checked by reading the code. It either reads the
numbers or it does not, and the only honest way to know is to show it known
numbers and count the ones it gets right.

The harness behind this renders synthetic student IDs: a number drawn in a real
font on card coloured paper, then blurred, noised, lit unevenly and tilted the
way a phone camera would see it. Then it runs the app's own reader over the
picture and reports how often the right answer came back.

    python tools/test_ocr_on_jvm.py
    python tools/test_ocr_on_jvm.py --verbose     show every case

Exits non-zero if exact reads drop below the bar the harness sets, so this can
sit in the build the same way the other layers do.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_logic_on_jvm import LIBS_DIR, ROSTER, collect_sources  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
HARNESS = ROOT / "tools" / "tests" / "OcrTestHarness.java"
CLASSES = ROOT / "build" / "ocr-classes"

JDK = Path(r"C:\Program Files\Java\jdk-26.0.1")
JAVAC = JDK / "bin" / "javac.exe"
JAVA = JDK / "bin" / "java.exe"


def run_ocr_tests(verbose: bool = False) -> None:
    jars = sorted(str(path) for path in LIBS_DIR.glob("*.jar"))
    if not jars:
        raise SystemExit(f"no jars in {LIBS_DIR} - the reader needs zxing-core")
    classpath = ";".join(jars)

    CLASSES.mkdir(parents=True, exist_ok=True)
    for stale in CLASSES.rglob("*.class"):
        stale.unlink()

    sources = collect_sources() + [str(HARNESS)]

    print("+ compiling " + str(len(sources)) + " files for the OCR tests")
    subprocess.run(
        [str(JAVAC), "--release", "8", "-nowarn", "-classpath", classpath,
         "-d", str(CLASSES)] + sources,
        check=True, cwd=str(ROOT))

    print("+ running OcrTestHarness\n")
    result = subprocess.run(
        [str(JAVA), "-cp", str(CLASSES) + ";" + classpath,
         "OcrTestHarness", str(ROSTER)],
        cwd=str(ROOT), capture_output=not verbose, text=True)

    if verbose:
        return

    output = result.stdout
    # The per case table is long. Keep the summary and any misses.
    lines = output.splitlines()
    keep = []
    inMisses = False
    for line in lines:
        if line.startswith("misses:"):
            inMisses = True
        if inMisses or not line.startswith(("arial", "verdana", "tahoma", "times",
                                            "cour", "calibri", "consola")):
            keep.append(line)
    print("\n".join(keep))

    if result.returncode != 0:
        raise SystemExit("OCR tests FAILED")


if __name__ == "__main__":
    run_ocr_tests(verbose="--verbose" in sys.argv)
