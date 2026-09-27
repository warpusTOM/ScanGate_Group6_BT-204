"""Release builds for IDCheck.

    python tools/build.py

Makes two variants:
  dist/IDCheck.exe        onefile, easiest to share
  dist/IDCheck/           onedir, friendlier to antivirus heuristics
                          (self-extracting onefile bootloaders get flagged more)

UPX is left off on purpose: packed binaries trigger heuristic scans.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DATA = [
    "--add-data", f"{ROOT / 'templates'};templates",
    "--add-data", f"{ROOT / 'static'};static",
    "--add-data", f"{ROOT / 'data'};data",
]
COMMON = [
    "--noconfirm", "--clean", "--noupx",
    "--name", "IDCheck",
    "--icon", str(ROOT / "assets" / "icon.ico"),
    "--version-file", str(ROOT / "tools" / "version_info.txt"),
    *DATA,
]


def build(mode: str) -> None:
    cmd = [sys.executable, "-m", "PyInstaller", *COMMON, mode,
           str(ROOT / "main.py")]
    print(" ".join(cmd))
    subprocess.run(cmd, cwd=ROOT, check=True)


if __name__ == "__main__":
    build("--onefile")
    build("--onedir")
    print("\ndone: dist/IDCheck.exe  and  dist/IDCheck/")
