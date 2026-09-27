"""Build ScanGate.apk with raw build-tools, no Gradle.

    python build.py            # -> dist/ScanGate.apk (debug-signed)
    python build.py --test     # SQL layer check first, then build
"""
from __future__ import annotations

import sqlite3
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SDK = Path(r"C:\Users\Administrator\android-sdk")
BT = SDK / "build-tools" / "35.0.0"
PLATFORM = SDK / "platforms" / "android-34"
ANDROID_JAR = PLATFORM / "android.jar"
JDK = Path(r"C:\Program Files\Java\jdk-26.0.1")
JAVA = JDK / "bin" / "java.exe"
JAVAC = JDK / "bin" / "javac.exe"

B = ROOT / "build"
OUT = ROOT / "dist"


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd)[:150])
    subprocess.run([str(c) for c in cmd], check=True, cwd=ROOT, **kw)


def test_sql() -> None:
    """Run the app's schema + queries against a real sqlite first."""
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE students ("
               "student_id TEXT PRIMARY KEY, full_name TEXT NOT NULL, "
               "course TEXT NOT NULL DEFAULT '', "
               "year_level TEXT NOT NULL DEFAULT '', "
               "section TEXT NOT NULL DEFAULT '')")
    db.execute("CREATE TABLE scan_logs ("
               "id INTEGER PRIMARY KEY AUTOINCREMENT, "
               "student_id TEXT NOT NULL, timestamp TEXT NOT NULL, "
               "note TEXT NOT NULL DEFAULT '', "
               "status TEXT NOT NULL DEFAULT '')")
    db.execute("INSERT INTO students VALUES (?,?,?,?,?)",
               ("20253152", "Jhon Lloyd Molino", "BT", "2", "BT-204"))
    row = db.execute(
        "SELECT student_id, full_name, course, year_level, section "
        "FROM students WHERE student_id = ?", ("20253152",)).fetchone()
    assert row and row[1] == "Jhon Lloyd Molino"
    db.execute("INSERT INTO scan_logs (student_id, timestamp, status) "
               "VALUES (?,?,?)", ("20253152", "2026-09-27 07:50:00", "ON TIME"))
    n = db.execute("SELECT COUNT(*) FROM scan_logs "
                   "WHERE substr(timestamp, 1, 10) = ? AND status = ?",
                   ("2026-09-27", "ON TIME")).fetchone()[0]
    assert n == 1
    db.close()
    print("sql layer: OK")


def build() -> Path:
    for d in (B, B / "gen", B / "classes", B / "dex", OUT):
        d.mkdir(parents=True, exist_ok=True)

    # 1. resources
    run([BT / "aapt2.exe", "compile", "--dir", "res", "-o", B / "res.zip"])
    run([BT / "aapt2.exe", "link", "-o", B / "base.apk", "-I", ANDROID_JAR,
         "--manifest", "AndroidManifest.xml",
         "--java", B / "gen", "-A", "assets",
         "--min-sdk-version", "24", "--target-sdk-version", "34",
         "--no-version-vectors", str(B / "res.zip")])

    # 2. java
    srcs = list((ROOT / "src").rglob("*.java")) + \
           list((B / "gen").rglob("*.java"))
    run([JAVAC, "--release", "8", "-classpath", ANDROID_JAR,
         "-d", B / "classes", *srcs])
    run([JDK / "bin" / "jar.exe", "cf", B / "classes.jar",
         "-C", B / "classes", "."])

    # 3. dex (d8's .bat is unusable; call the main class directly)
    run([JAVA, "-cp", BT / "lib" / "d8.jar", "com.android.tools.r8.D8",
         "--min-api", "24", "--lib", ANDROID_JAR,
         "--output", B / "dex", B / "classes.jar"])

    # 4. fold classes.dex into the apk, preserving entry compression
    unsigned = B / "unsigned.apk"
    with zipfile.ZipFile(B / "base.apk") as zin, \
            zipfile.ZipFile(unsigned, "w") as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.writestr("classes.dex", (B / "dex" / "classes.dex").read_bytes())

    # 5. align + sign (debug key, generated once)
    ks = ROOT / "debug.keystore"
    if not ks.exists():
        run([JDK / "bin" / "keytool.exe", "-genkeypair", "-keystore", ks,
             "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-storepass", "android",
             "-keypass", "android",
             "-dname", "CN=Android Debug,O=Android,C=US"])
    run([BT / "zipalign.exe", "-f", "-p", "4", unsigned, B / "aligned.apk"])
    final = OUT / "ScanGate.apk"
    run([JAVA, "-cp", BT / "lib" / "apksigner.jar",
         "com.android.apksigner.ApkSignerTool", "sign",
         "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
         "--out", final, B / "aligned.apk"])
    return final


def verify(apk: Path) -> None:
    run([JAVA, "-cp", BT / "lib" / "apksigner.jar",
         "com.android.apksigner.ApkSignerTool", "verify", "--verbose", apk])
    run([BT / "aapt2.exe", "dump", "badging", apk])


if __name__ == "__main__":
    if "--test" in sys.argv:
        test_sql()
    apk = build()
    verify(apk)
    print(f"\nAPK -> {apk}  ({apk.stat().st_size / 1e6:.1f} MB)")
