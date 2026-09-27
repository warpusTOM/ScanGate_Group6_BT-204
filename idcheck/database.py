"""SQLite persistence for IDCheck — fully offline."""
from __future__ import annotations

import sqlite3
import threading
from datetime import date, datetime
from pathlib import Path

from .constants import DB_PATH
from .models import ScanRecord, Student

SCHEMA = """
CREATE TABLE IF NOT EXISTS students (
    student_id TEXT PRIMARY KEY,
    full_name  TEXT NOT NULL,
    gmail      TEXT NOT NULL DEFAULT '',
    course     TEXT NOT NULL DEFAULT '',
    year_level TEXT NOT NULL DEFAULT '',
    section    TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS scan_logs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id TEXT NOT NULL,
    timestamp  TEXT NOT NULL,
    note       TEXT NOT NULL DEFAULT '',
    emailed    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_scan_logs_sid ON scan_logs(student_id);
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


class Database:
    """Encapsulates all SQL — the rest of the app never writes queries.

    Flask serves requests on worker threads, so the connection is opened
    with check_same_thread=False and every access goes through one lock.
    RLock because get_student() calls find_student().
    """

    def __init__(self, path: str | Path = DB_PATH):
        self.path = Path(path)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(SCHEMA)
            self._ensure_columns()
            self._conn.commit()

    def _ensure_columns(self) -> None:
        """Add columns that older database files don't have yet."""
        cols = {r[1] for r in self._conn.execute("PRAGMA table_info(scan_logs)")}
        if "status" not in cols:
            self._conn.execute(
                "ALTER TABLE scan_logs ADD COLUMN status TEXT NOT NULL DEFAULT ''")

    # ---------------- settings ----------------
    def get_setting(self, key: str, default: str = "") -> str:
        with self._lock:
            row = self._conn.execute(
                "SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return row[0] if row else default

    def set_setting(self, key: str, value: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO settings (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, value))
            self._conn.commit()

    def __enter__(self) -> "Database":
        return self

    def __exit__(self, *_exc) -> None:
        self.close()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # ---------------- students ----------------
    def upsert_student(self, s: Student) -> None:
        with self._lock:
            self._conn.execute(
                """INSERT INTO students (student_id, full_name, gmail, course,
                                         year_level, section)
                   VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT(student_id) DO UPDATE SET
                       full_name=excluded.full_name, gmail=excluded.gmail,
                       course=excluded.course, year_level=excluded.year_level,
                       section=excluded.section""",
                (s.student_id, s.full_name, s.gmail, s.course,
                 s.year_level, s.section),
            )
            self._conn.commit()

    def find_student(self, student_id: str) -> Student | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM students WHERE student_id = ?", (student_id,)
            ).fetchone()
        return self._row_to_student(row) if row else None

    def get_student(self, student_id: str) -> Student:
        student = self.find_student(student_id)
        if student is None:
            raise KeyError(f"ID '{student_id}' is not registered.")
        return student

    def all_students(self) -> list[Student]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM students ORDER BY full_name").fetchall()
        return [self._row_to_student(r) for r in rows]

    def count_students(self) -> int:
        with self._lock:
            return self._conn.execute(
                "SELECT COUNT(*) FROM students").fetchone()[0]

    @staticmethod
    def _row_to_student(row: sqlite3.Row) -> Student:
        return Student(
            student_id=row["student_id"], full_name=row["full_name"],
            gmail=row["gmail"], course=row["course"],
            year_level=row["year_level"], section=row["section"],
        )

    # ---------------- scan logs ----------------
    def log_scan(self, rec: ScanRecord) -> int:
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO scan_logs (student_id, timestamp, note, status) "
                "VALUES (?, ?, ?, ?)",
                (rec.student_id, rec.timestamp.isoformat(), rec.note,
                 rec.status),
            )
            self._conn.commit()
            rec.id = cur.lastrowid
            return rec.id

    def recent_scans(self, limit: int = 50) -> list[ScanRecord]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM scan_logs ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [self._row_to_scan(r) for r in rows]

    def count_scans(self) -> int:
        with self._lock:
            return self._conn.execute(
                "SELECT COUNT(*) FROM scan_logs").fetchone()[0]

    def count_scans_today(self) -> int:
        with self._lock:
            return self._conn.execute(
                "SELECT COUNT(*) FROM scan_logs "
                "WHERE substr(timestamp, 1, 10) = ?",
                (date.today().isoformat(),),
            ).fetchone()[0]

    def count_scans_today_with_status(self, status: str) -> int:
        with self._lock:
            return self._conn.execute(
                "SELECT COUNT(*) FROM scan_logs "
                "WHERE substr(timestamp, 1, 10) = ? AND status = ?",
                (date.today().isoformat(), status),
            ).fetchone()[0]

    def count_scans_for(self, student_id: str) -> int:
        with self._lock:
            return self._conn.execute(
                "SELECT COUNT(*) FROM scan_logs WHERE student_id = ?",
                (student_id,),
            ).fetchone()[0]

    @staticmethod
    def _row_to_scan(row: sqlite3.Row) -> ScanRecord:
        return ScanRecord(
            student_id=row["student_id"],
            timestamp=datetime.fromisoformat(row["timestamp"]),
            note=row["note"], status=row["status"], id=row["id"],
        )
