"""IDCheckSystem: one facade over the database and the importer."""
from __future__ import annotations

from datetime import datetime, time, timedelta
from pathlib import Path

from .constants import (DB_PATH, DEFAULT_START_TIME, EARLY_BEFORE_MINUTES,
                        LATE_AFTER_MINUTES)
from .database import Database
from .importer import load_students_csv
from .models import ScanRecord, Student


class IDCheckSystem:
    def __init__(self, db_path: str | Path = DB_PATH):
        self.db = Database(db_path)

    # students ---------------------------------------------------------
    def import_students(self, csv_path: str | Path) -> tuple[int, int]:
        added = updated = 0
        for s in load_students_csv(csv_path):
            if self.db.find_student(s.student_id) is not None:
                updated += 1
            else:
                added += 1
            self.db.upsert_student(s)
        return added, updated

    def lookup(self, student_id: str) -> Student:
        return self.db.get_student(student_id.strip())

    # scanning ---------------------------------------------------------
    def scan(self, student_id: str, note: str = "",
             when: datetime | None = None) -> tuple[Student, ScanRecord]:
        student = self.lookup(student_id)
        ts = when or datetime.now()
        rec = ScanRecord(student_id=student.student_id, timestamp=ts,
                         note=note.strip(), status=self._classify(ts))
        self.db.log_scan(rec)
        return student, rec

    # ---- time notes ----------------------------------------------------
    def time_window(self) -> tuple[time, int, int]:
        """(class start, early-before minutes, late-after minutes)."""
        raw = self.db.get_setting("start_time", DEFAULT_START_TIME)
        try:
            start = datetime.strptime(raw, "%H:%M").time()
        except ValueError:
            start = datetime.strptime(DEFAULT_START_TIME, "%H:%M").time()
        try:
            early = int(self.db.get_setting("early_before",
                                            str(EARLY_BEFORE_MINUTES)))
        except ValueError:
            early = EARLY_BEFORE_MINUTES
        try:
            late = int(self.db.get_setting("late_after",
                                           str(LATE_AFTER_MINUTES)))
        except ValueError:
            late = LATE_AFTER_MINUTES
        return start, early, late

    def set_time_window(self, start_time: str, early_before: int,
                        late_after: int) -> None:
        datetime.strptime(start_time, "%H:%M")  # raises on bad input
        if early_before < 0 or late_after < 0:
            raise ValueError("minutes cannot be negative")
        self.db.set_setting("start_time", start_time)
        self.db.set_setting("early_before", str(early_before))
        self.db.set_setting("late_after", str(late_after))

    def _classify(self, ts: datetime) -> str:
        start, early_before, late_after = self.time_window()
        start_dt = datetime.combine(ts.date(), start)
        if ts < start_dt - timedelta(minutes=early_before):
            return "EARLY"
        if ts <= start_dt + timedelta(minutes=late_after):
            return "ON TIME"
        return "LATE"

    # info ---------------------------------------------------------------
    def recent_scans(self, limit: int = 50) -> list[ScanRecord]:
        return self.db.recent_scans(limit)

    def stats(self) -> dict:
        return {
            "students": self.db.count_students(),
            "scans_total": self.db.count_scans(),
            "scans_today": self.db.count_scans_today(),
            "late_today": self.db.count_scans_today_with_status("LATE"),
            "ontime_today": self.db.count_scans_today_with_status("ON TIME"),
        }

    def close(self) -> None:
        self.db.close()
