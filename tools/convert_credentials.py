"""Convert the school portal's credentials export into ScanGate's CSV.

    python tools/convert_credentials.py <input.csv> <output.csv>

The portal export looks like:
    section,student_no,surname,first_name,access_key

ScanGate wants:
    student_id,full_name,gmail,course,year_level,section

Rules:
- full_name  = first_name + surname
- student_id = student_no, or access_key when student_no is blank
- course / year_level are parsed out of the section (BT-204 -> BT, 2)
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path


def convert(src: Path, dst: Path) -> None:
    out_rows = []
    seen = {}
    with src.open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            sid = (row.get("student_no") or "").strip()
            key = (row.get("access_key") or "").strip()
            if not sid:
                sid = key
            if not sid:
                continue  # nothing to identify this student with
            full = f"{(row.get('first_name') or '').strip()} " \
                   f"{(row.get('surname') or '').strip()}".strip().title()
            section = (row.get("section") or "").strip()
            course, _, rest = section.partition("-")
            year = rest[:1] if rest else ""
            seen[sid] = [sid, full, "", course, year, section]
    out_rows = list(seen.values())

    with dst.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["student_id", "full_name", "gmail",
                         "course", "year_level", "section"])
        writer.writerows(out_rows)
    print(f"{len(out_rows)} students -> {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: python tools/convert_credentials.py <in.csv> <out.csv>")
    convert(Path(sys.argv[1]), Path(sys.argv[2]))
