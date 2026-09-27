"""Domain classes: Student and ScanRecord."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass
class Student:
    """A registered student. Validates its own data on creation."""

    student_id: str
    full_name: str
    gmail: str = ""
    course: str = ""
    year_level: str = ""
    section: str = ""

    def __post_init__(self) -> None:
        self.student_id = self.student_id.strip()
        self.full_name = self.full_name.strip()
        self.gmail = self.gmail.strip()
        if not self.student_id:
            raise ValueError("student_id is required")
        if not self.full_name:
            raise ValueError("full_name is required")
        if self.gmail and "@" not in self.gmail:
            raise ValueError(f"invalid email address: {self.gmail}")

    @property
    def can_receive_email(self) -> bool:
        return bool(self.gmail)

    @property
    def year_section(self) -> str:
        parts = [p for p in (self.course, self.year_level, self.section) if p]
        return " ".join(parts) or "(no course info)"

    def __str__(self) -> str:
        return f"{self.full_name} ({self.student_id})"


@dataclass
class ScanRecord:
    """One verification event — who was scanned, when, and whether an
    email notification went out."""

    student_id: str
    timestamp: datetime
    note: str = ""
    emailed: bool = False
    id: int | None = None

    def __str__(self) -> str:
        flag = " emailed" if self.emailed else ""
        return f"#{self.id or '-'} {self.timestamp:%Y-%m-%d %H:%M:%S} {self.student_id}{flag}"
