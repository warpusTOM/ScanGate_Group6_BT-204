"""CSV student import with flexible column names.

Accepts common header variants so a real registrar/adviser export usually
works without editing: e.g. "Student No", "ID", "Name", "Email", "Course",
"Grade", "Section".

Also accepts the school portal's credentials export as-is:

    section,student_no,surname,first_name,access_key

Rules for that format:
- full_name  = first_name + surname
- student_id = student_no, or access_key when student_no is blank
- course / year_level are parsed from the section (BT-204 -> BT, 2)
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
    "first_name": {"first_name", "firstname", "first name", "given name",
                   "given_name"},
    "surname": {"surname", "last_name", "lastname", "last name",
                "family_name", "family name"},
    "access_key": {"access_key", "access key", "key"},
    "gmail": {"gmail", "email", "e-mail", "gmail_address", "gmail address",
              "email_address", "email address"},
    "course": {"course", "program", "strand", "track"},
    "year_level": {"year_level", "year level", "year", "yr", "grade",
                   "grade_level", "grade level"},
    "section": {"section", "sec", "block", "class"},
}
REQUIRED = ("student_id", "full_name")
STUDENT_FIELDS = ("student_id", "full_name", "gmail", "course",
                  "year_level", "section")


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
        # portal export: name split into two columns, key as id fallback
        if "full_name" in missing and {"first_name", "surname"} <= set(mapping):
            missing.remove("full_name")
        if "student_id" in missing and "access_key" in mapping:
            missing.remove("student_id")
        if missing:
            accepted = sorted(ALIASES[missing[0]])
            raise ValueError(
                f"CSV is missing required column '{missing[0]}'. "
                f"Accepted header names: {', '.join(accepted)}")

        for line_no, row in enumerate(reader, start=2):  # 1 = header
            data = {canon: (row.get(actual) or "").strip()
                    for canon, actual in mapping.items()}

            # build full_name from parts when there is no full name column
            if not data.get("full_name") and "first_name" in mapping:
                data["full_name"] = (
                    f"{data.get('first_name', '')} {data.get('surname', '')}"
                    .strip().title())

            # fall back to the access key when the number is blank
            if not data.get("student_id") and data.get("access_key"):
                data["student_id"] = data["access_key"]
            if not data.get("student_id"):
                continue  # nothing to identify this student with

            # derive course + year from the section when the file lacks them
            section = data.get("section", "")
            if section and not data.get("course"):
                course, _, rest = section.partition("-")
                data["course"] = course
                if not data.get("year_level"):
                    data["year_level"] = rest[:1]

            try:
                students.append(Student(
                    **{k: v for k, v in data.items() if k in STUDENT_FIELDS}))
            except ValueError as exc:
                raise ValueError(f"Row {line_no}: {exc}") from exc
    return students
