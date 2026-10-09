"""Release builds for AttendGate.

    python tools/build.py

Makes two variants:
  dist/AttendGate.exe      onefile, easiest to share
  dist/AttendGate/         onedir, friendlier to antivirus heuristics
                           (self-extracting onefile bootloaders get flagged more)

UPX is left off on purpose: packed binaries trigger heuristic scans.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DATA = [
    "--add-data", f"{ROOT / 'templates'};templates",
    "--add-data", f"{ROOT / 'static'};static",
    "--add-data", f"{ROOT / 'data'};data",
]
COMMON = [
    "--noconfirm", "--clean", "--noupx", "--windowed",
    "--name", "AttendGate",
    "--icon", str(ROOT / "assets" / "icon.ico"),
    "--version-file", str(ROOT / "tools" / "version_info.txt"),
    *DATA,
]


def _remove_stale(mode: str) -> None:
    """PyInstaller overwrites its own outputs with --noconfirm, and its
    COLLECT step removes stale onedir folders itself, so there is nothing
    to pre-delete here. The retry in build() covers AV file locks."""
    return


def build(mode: str) -> None:
    _remove_stale(mode)
    cmd = [sys.executable, "-m", "PyInstaller", *COMMON, mode,
           str(ROOT / "main.py")]
    print(" ".join(cmd))
    # fresh binaries get AV-scanned mid-write sometimes; one retry rides it out
    for attempt in range(2):
        try:
            subprocess.run(cmd, cwd=ROOT, check=True)
            return
        except subprocess.CalledProcessError:
            if attempt == 1:
                raise
            print("build flaked, retrying in 5s...")
            time.sleep(5)


if __name__ == "__main__":
    build("--onefile")
    build("--onedir")
    print("\ndone: dist/AttendGate.exe  and  dist/AttendGate/")
