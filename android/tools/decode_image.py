"""Read a barcode or QR code out of a picture file.

Photograph a student ID, run this, and see exactly what the code on the card
holds. Useful for checking a card without a phone, and for working out why a
scan did not find a student.

It compiles the app's own BarcodeDecoder plus a small command-line wrapper, so
the code path being tested is the same one that runs on the phone.

    python tools/decode_image.py photo.jpg
    python tools/decode_image.py "C:/cards/*.jpg"     # shell expands this
    python tools/decode_image.py a.jpg b.jpg c.jpg

Nothing is written anywhere. The picture never leaves this machine.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_logic_on_jvm import PURE_SOURCE_FILES, PACKAGE_DIR, LIBS_DIR  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
WRAPPER = ROOT / "tools" / "DecodeImageBarcode.java"
CLASSES = ROOT / "build" / "image-tools-classes"

JDK = Path(r"C:\Program Files\Java\jdk-26.0.1")
JAVAC = JDK / "bin" / "javac.exe"
JAVA = JDK / "bin" / "java.exe"


def compile_wrapper() -> str:
    jars = sorted(str(path) for path in LIBS_DIR.glob("*.jar"))
    if not jars:
        raise SystemExit(f"no jars in {LIBS_DIR} - the decoder needs zxing-core")
    classpath = ";".join(jars)

    sources = [str(PACKAGE_DIR / name) for name in PURE_SOURCE_FILES]
    sources.append(str(WRAPPER))

    CLASSES.mkdir(parents=True, exist_ok=True)
    for stale in CLASSES.rglob("*.class"):
        stale.unlink()

    subprocess.run(
        [str(JAVAC), "--release", "8", "-nowarn", "-classpath", classpath,
         "-d", str(CLASSES)] + sources,
        check=True, cwd=str(ROOT))
    return classpath


def main() -> None:
    images = sys.argv[1:]
    if not images:
        raise SystemExit("Give me at least one image file:\n"
                         "  python tools/decode_image.py photo.jpg")

    missing = [name for name in images if not Path(name).exists()]
    if missing:
        raise SystemExit("These files do not exist:\n  " + "\n  ".join(missing))

    classpath = compile_wrapper()
    subprocess.run(
        [str(JAVA), "-cp", str(CLASSES) + ";" + classpath,
         "DecodeImageBarcode"] + images,
        cwd=str(ROOT))


if __name__ == "__main__":
    main()
