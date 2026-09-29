"""Domain classes: Student and ScanRecord."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .timefmt import stamp_12h


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
    """One verification event: who scanned, when, and whether they were
    EARLY, ON TIME, or LATE against the class start time."""

    student_id: str
    timestamp: datetime
    note: str = ""
    status: str = ""
    id: int | None = None

    def __str__(self) -> str:
        return (f"#{self.id or '-'} {stamp_12h(self.timestamp)} "
                f"{self.student_id} {self.status}")
