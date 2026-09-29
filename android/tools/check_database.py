"""Test layer 2 - run the app's real SQL against a real SQLite.

SQLiteOpenHelper cannot run off a phone, but the SQL inside it is ordinary
SQLite. So this pulls the CREATE statements straight out of the Java source,
builds the same tables in a throwaway database, and then runs every query the
app runs, with realistic values.

That catches the things a compiler cannot: a misspelled column, a query that
only breaks once there is a row in the table, a date comparison that silently
matches nothing.

It also checks the bundled roster file, because a broken students.csv means an
app that installs fine and then finds nobody.

    python tools/check_database.py
"""
from __future__ import annotations

import csv
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "scangate" / "app"
ROSTER = ROOT / "app" / "src" / "main" / "assets" / "students.csv"

DATABASE_JAVA = PACKAGE_DIR / "data" / "ScanGateDatabase.java"
DAO_FILES = [
    PACKAGE_DIR / "data" / "StudentDao.java",
    PACKAGE_DIR / "data" / "ScanLogDao.java",
    PACKAGE_DIR / "data" / "SettingsDao.java",
]

EXPECTED_TABLES = {"students", "scan_logs", "settings"}

# The queries the app is expected to run. If someone edits a query, this list
# has to change with it - otherwise the edit would quietly go untested.
EXPECTED_QUERIES = {
    "SELECT student_id, full_name, course, year_level, section FROM students WHERE student_id = ?",
    "SELECT COUNT(*) FROM students",
    "SELECT student_id FROM students",
    "SELECT id, student_id, timestamp, status, note FROM scan_logs ORDER BY id DESC LIMIT ?",
    "SELECT COUNT(*) FROM scan_logs WHERE substr(timestamp, 1, 10) = ?",
    "SELECT COUNT(*) FROM scan_logs",
    "SELECT value FROM settings WHERE key = ?",
}

# The alias table from RosterCsvReader.java, repeated here so the check is
# independent of the Java. A header must match one of these to be understood.
HEADER_ALIASES = {
    "student_id": {"student_id", "id", "student_no", "student no", "student no.",
                   "student_number", "student number", "id_no", "id no",
                   "id number", "lrn"},
    "full_name": {"full_name", "fullname", "full name", "name",
                  "student_name", "student name"},
    "first_name": {"first_name", "firstname", "first name", "given name", "given_name"},
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


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def join_adjacent_literals(java_text: str) -> str:
    """Turns "SELECT ... " + "FROM ..." into one literal, the way Java does."""
    return re.sub(r'"\s*\+\s*"', "", java_text)


def unescape(java_literal: str) -> str:
    return (java_literal
            .replace('\\"', '"')
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\"))


def normalize(sql: str) -> str:
    return " ".join(sql.split())


def extract_create_statements() -> dict:
    text = join_adjacent_literals(read(DATABASE_JAVA))
    found = re.findall(
        r'public static final String (CREATE_\w+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', text)
    return {name: unescape(value) for name, value in found}


def extract_queries() -> set:
    queries = set()
    for path in DAO_FILES:
        text = join_adjacent_literals(read(path))
        for literal in re.findall(r'"((?:[^"\\]|\\.)*)"', text):
            sql = unescape(literal)
            if sql.strip().upper().startswith("SELECT"):
                queries.add(normalize(sql))
    return queries


def build_schema(connection: sqlite3.Connection, statements: dict) -> None:
    for name, sql in statements.items():
        connection.execute(sql)


def load_roster() -> list:
    with open(ROSTER, newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle))
    return rows


def check_roster_file() -> list:
    problems = []
    if not ROSTER.exists():
        return [f"roster file missing: {ROSTER}"]

    with open(ROSTER, newline="", encoding="utf-8-sig") as handle:
        header = next(csv.reader(handle))

    lowered = {name.strip().lower() for name in header}
    known = set()
    for aliases in HEADER_ALIASES.values():
        known |= aliases
    unknown = sorted(lowered - known)
    if unknown:
        problems.append(f"students.csv has headers nothing understands: {unknown}")

    has_id = bool(lowered & HEADER_ALIASES["student_id"]) or \
        bool(lowered & HEADER_ALIASES["access_key"])
    has_name = bool(lowered & HEADER_ALIASES["full_name"]) or \
        bool(lowered & HEADER_ALIASES["first_name"])
    if not has_id:
        problems.append("students.csv has no student number column")
    if not has_name:
        problems.append("students.csv has no name column")

    rows = load_roster()
    if len(rows) < 50:
        problems.append(f"students.csv only has {len(rows)} rows, that looks wrong")

    ids = [row.get("student_id", "").strip() for row in rows]
    empty = sum(1 for value in ids if not value)
    if empty:
        problems.append(f"students.csv has {empty} row(s) with a blank student number")

    duplicates = len(ids) - len(set(ids))
    if duplicates:
        problems.append(f"students.csv has {duplicates} duplicate student number(s)")

    return problems


def run_database_checks() -> None:
    statements = extract_create_statements()
    if not statements:
        raise SystemExit("could not find any CREATE statement in ScanGateDatabase.java")

    connection = sqlite3.connect(":memory:")
    build_schema(connection, statements)

    tables = {row[0] for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table'")}
    missing = EXPECTED_TABLES - tables
    if missing:
        raise SystemExit(f"schema is missing tables: {sorted(missing)}")

    # every query the app runs must be one we know about
    queries = extract_queries()
    unexpected = queries - EXPECTED_QUERIES
    if unexpected:
        raise SystemExit("untested query found in the DAO files:\n  "
                         + "\n  ".join(sorted(unexpected)))

    # fill the tables with realistic rows
    rows = load_roster()
    connection.executemany(
        "INSERT OR REPLACE INTO students "
        "(student_id, full_name, course, year_level, section) VALUES (?, ?, ?, ?, ?)",
        [(row.get("student_id", ""), row.get("full_name", ""),
          row.get("course", ""), row.get("year_level", ""), row.get("section", ""))
         for row in rows if row.get("student_id", "").strip()])

    connection.executemany(
        "INSERT INTO scan_logs (student_id, timestamp, status, note) VALUES (?, ?, ?, ?)",
        [("20253152", "2026-09-27 07:50:00", "ON TIME", ""),
         ("20263748", "2026-09-27 08:05:00", "LATE", ""),
         ("20253152", "2026-09-27 08:30:00", "LATE", ""),
         ("ALIMEN", "2026-09-26 07:59:00", "ON TIME", "")])

    connection.execute("INSERT INTO settings (key, value) VALUES (?, ?)",
                       ("start_time", "08:00"))

    # now run each query for real
    first_id = rows[0]["student_id"].strip()
    results = {
        "find a student":
            connection.execute(
                "SELECT student_id, full_name, course, year_level, section "
                "FROM students WHERE student_id = ?", (first_id,)).fetchone(),
        "count students":
            connection.execute("SELECT COUNT(*) FROM students").fetchone()[0],
        "list every student number":
            connection.execute("SELECT student_id FROM students").fetchall(),
        "recent scans":
            connection.execute(
                "SELECT id, student_id, timestamp, status, note FROM scan_logs "
                "ORDER BY id DESC LIMIT ?", ("25",)).fetchall(),
        "scans on a day":
            connection.execute(
                "SELECT COUNT(*) FROM scan_logs WHERE substr(timestamp, 1, 10) = ?",
                ("2026-09-27",)).fetchone()[0],
        "late scans on a day":
            connection.execute(
                "SELECT COUNT(*) FROM scan_logs WHERE substr(timestamp, 1, 10) = ? "
                "AND status = ?", ("2026-09-27", "LATE")).fetchone()[0],
        "all scans":
            connection.execute("SELECT COUNT(*) FROM scan_logs").fetchone()[0],
        "read a setting":
            connection.execute("SELECT value FROM settings WHERE key = ?",
                               ("start_time",)).fetchone()[0],
        "missing setting falls back":
            connection.execute("SELECT value FROM settings WHERE key = ?",
                               ("not_there",)).fetchone(),
    }

    problems = []
    if results["find a student"] is None:
        problems.append("looking a student up by number returned nothing")
    if results["count students"] != len([r for r in rows if r["student_id"].strip()]):
        problems.append("student count does not match the roster file")
    if len(results["list every student number"]) != results["count students"]:
        problems.append("listing every student number returned a different count "
                        "than counting them")
    if results["scans on a day"] != 3:
        problems.append(f"scans on 2026-09-27 came back as "
                        f"{results['scans on a day']}, expected 3")
    if results["late scans on a day"] != 2:
        problems.append(f"late scans on 2026-09-27 came back as "
                        f"{results['late scans on a day']}, expected 2")
    if results["all scans"] != 4:
        problems.append("total scan count is wrong")
    if results["read a setting"] != "08:00":
        problems.append("reading a saved setting returned the wrong value")
    if results["missing setting falls back"] is not None:
        problems.append("a setting that does not exist should return no row")
    if len(results["recent scans"]) != 4:
        problems.append("recent scans did not come back")
    if results["recent scans"] and results["recent scans"][0][1] != "ALIMEN":
        problems.append("recent scans is not newest first")

    # the date filter is the one that silently breaks if a timestamp ever
    # stops being stored in 24-hour ISO form
    connection.execute("INSERT INTO scan_logs (student_id, timestamp, status, note) "
                       "VALUES (?, ?, ?, ?)",
                       ("20253152", "2026-09-27 20:27:28", "LATE", ""))
    evening = connection.execute(
        "SELECT COUNT(*) FROM scan_logs WHERE substr(timestamp, 1, 10) = ?",
        ("2026-09-27",)).fetchone()[0]
    if evening != 4:
        problems.append("an evening scan was not counted on its own day - "
                        "the stored timestamp may not be 24-hour any more")

    connection.close()

    problems += check_roster_file()

    if problems:
        print("\ndatabase checks FAILED:")
        for problem in problems:
            print("  -", problem)
        raise SystemExit(1)

    print(f"database checks: OK ({len(tables)} tables, {len(queries)} queries, "
          f"{len(rows)} roster rows)")


def check_database() -> None:
    run_database_checks()


if __name__ == "__main__":
    check_database()
