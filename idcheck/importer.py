"""CSV student import with flexible column names.

Accepts common header variants so a real registrar/adviser export usually
works without editing: e.g. "Student No", "ID", "Name", "Email", "Course",
"Grade", "Section".
"""
from __future__ import annotations

import csv
from pathlib import Path

from .models import Student

ALIASES: dict[str, set[str]] = {
    "student_id": {"student_id", "id", "student_no", "student no", "student no.",
                   "student_number", "student number", "id_no", "id no",
                   "id number", "lrn"},
    "full_name": {"full_name", "fullname", "full name", "name",
                  "student_name", "student name"},
    "gmail": {"gmail", "email", "e-mail", "gmail_address", "gmail address",
              "email_address", "email address"},
    "course": {"course", "program", "strand", "track"},
    "year_level": {"year_level", "year level", "year", "yr", "grade",
                   "grade_level", "grade level"},
    "section": {"section", "sec", "block", "class"},
}
REQUIRED = ("student_id", "full_name")


def _map_headers(fieldnames: list[str] | None) -> dict[str, str]:
    """Map canonical field -> actual CSV header (first matching alias)."""
    lowered = {h.strip().lower(): h for h in (fieldnames or [])}
    mapping: dict[str, str] = {}
    for canon, aliases in ALIASES.items():
        for alias in aliases:
            if alias in lowered:
                mapping[canon] = lowered[alias]
                break
    return mapping


def load_students_csv(path: str | Path) -> list[Student]:
    students: list[Student] = []
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        mapping = _map_headers(reader.fieldnames)
        missing = [c for c in REQUIRED if c not in mapping]
        if missing:
            accepted = sorted(ALIASES[missing[0]])
            raise ValueError(
                f"CSV is missing required column '{missing[0]}'. "
                f"Accepted header names: {', '.join(accepted)}")
        for line_no, row in enumerate(reader, start=2):  # 1 = header
            data = {canon: (row.get(actual) or "").strip()
                    for canon, actual in mapping.items()}
            if not data.get("student_id"):
                continue  # skip blank lines
            try:
                students.append(Student(**data))
            except ValueError as exc:
                raise ValueError(f"Row {line_no}: {exc}") from exc
    return students
