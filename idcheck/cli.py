"""Terminal scanner, no dependencies. Handy for quick demos."""
from __future__ import annotations

from .system import IDCheckSystem
from .timefmt import stamp_12h

HELP = ("commands:  :import <csv>   :list   :logs [n]   :stats   :quit\n"
        "anything else is treated as a student number to scan")


def _print_card(student, rec) -> None:
    print("  +-- VERIFIED " + "-" * 40)
    print(f"  | Name    : {student.full_name}")
    print(f"  | ID      : {student.student_id}")
    print(f"  | Section : {student.section or '-'}")
    print(f"  | Time    : {stamp_12h(rec.timestamp)}")
    print(f"  | Status  : {rec.status}")
    print("  +" + "-" * 52)


def _command(system: IDCheckSystem, raw: str) -> str | None:
    parts = raw.split(maxsplit=1)
    cmd = parts[0].lower()
    arg = parts[1] if len(parts) > 1 else ""

    if cmd == ":quit":
        return "quit"
    if cmd == ":help":
        print(HELP)
    elif cmd == ":import":
        try:
            added, updated = system.import_students(arg.strip().strip('"'))
            print(f"import done: {added} added, {updated} updated")
        except (ValueError, OSError) as exc:
            print(f"import failed: {exc}")
    elif cmd == ":list":
        for s in system.db.all_students():
            print(f"  {s.student_id:<12} {s.full_name:<28} {s.year_section}")
        print(f"({system.db.count_students()} students)")
    elif cmd == ":logs":
        try:
            n = int(arg) if arg else 10
        except ValueError:
            n = 10
        for rec in system.recent_scans(n):
            print(f"  {rec}")
    elif cmd == ":stats":
        st = system.stats()
        print(f"  students: {st['students']}  scans today: {st['scans_today']}  "
              f"on time: {st['ontime_today']}  late: {st['late_today']}  "
              f"total scans: {st['scans_total']}")
    else:
        print(f"unknown command {cmd} (:help)")
    return None


def run_cli(system: IDCheckSystem) -> None:
    print("IDCheck terminal scanner  (:help for commands)")
    while True:
        try:
            raw = input("\nscan id > ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not raw:
            continue
        if raw.startswith(":"):
            if _command(system, raw) == "quit":
                break
            continue
        try:
            student, rec = system.scan(raw)
        except KeyError:
            print(f"  !! ID '{raw}' is NOT REGISTERED")
            continue
        _print_card(student, rec)
    print("bye")
