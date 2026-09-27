"""IDCheckSystem: one facade over the database, importer, and notifier."""
from __future__ import annotations

from datetime import datetime
from pathlib import Path

from .constants import (DB_PATH, EMAIL_ENABLED, EMAIL_SUBJECT,
                        GMAIL_ADDRESS, GMAIL_APP_PASSWORD)
from .database import Database
from .importer import load_students_csv
from .models import ScanRecord, Student
from .notifier import GmailNotifier, Notifier, NullNotifier


class IDCheckSystem:
    def __init__(self, db_path: str | Path = DB_PATH,
                 notifier: Notifier | None = None):
        self.db = Database(db_path)
        if notifier is not None:
            self.notifier = notifier
        elif EMAIL_ENABLED and GMAIL_ADDRESS and GMAIL_APP_PASSWORD:
            self.notifier = GmailNotifier(GMAIL_ADDRESS, GMAIL_APP_PASSWORD)
        else:
            self.notifier = NullNotifier()

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
    def scan(self, student_id: str, note: str = "") -> tuple[Student, ScanRecord]:
        student = self.lookup(student_id)
        rec = ScanRecord(student_id=student.student_id,
                         timestamp=datetime.now(), note=note.strip())
        self.db.log_scan(rec)
        if self._notify(student, rec):
            self.db.mark_emailed(rec.id)
            rec.emailed = True
        return student, rec

    def _notify(self, student: Student, rec: ScanRecord) -> bool:
        # a failed email must never break a scan, so swallow errors here
        if not student.can_receive_email:
            return False
        body = (
            f"Hi {student.full_name},\n\n"
            f"Your student ID ({student.student_id}) was scanned and verified "
            f"on {rec.timestamp:%B %d, %Y} at {rec.timestamp:%I:%M %p}.\n"
        )
        if rec.note:
            body += f"Note: {rec.note}\n"
        body += ("\nIf this was not you, please tell your instructor "
                 "or the registrar.\n\n- IDCheck")
        try:
            return self.notifier.send(student, EMAIL_SUBJECT, body)
        except Exception:
            return False

    # info ---------------------------------------------------------------
    def recent_scans(self, limit: int = 50) -> list[ScanRecord]:
        return self.db.recent_scans(limit)

    def check_student_emails(self) -> list[dict]:
        """Online-only: validate every stored gmail through EVA.

        Returns one row per student that has a gmail:
        {"student_id", "full_name", "gmail", "result"} where result is
        None when there is no internet or the API is down.
        """
        from .emailcheck import check_email

        report = []
        for s in self.db.all_students():
            if not s.gmail:
                continue
            report.append({
                "student_id": s.student_id,
                "full_name": s.full_name,
                "gmail": s.gmail,
                "result": check_email(s.gmail),
            })
        return report

    def stats(self) -> dict:
        return {
            "students": self.db.count_students(),
            "scans_total": self.db.count_scans(),
            "scans_today": self.db.count_scans_today(),
            "email_enabled": not isinstance(self.notifier, NullNotifier),
        }

    def close(self) -> None:
        self.db.close()
