"""12-hour clock formatting for everything a human reads.

Storage stays ISO 24-hour (`timestamp.isoformat()`) because the database
sorts and date-slices on it — `count_scans_today` does
`substr(timestamp, 1, 10)`, and a 12-hour string would break that and
lose the ordering. Only display strings pass through here.

Format: no leading zero on the hour, English AM/PM.
    08:05  ->  8:05 AM
    12:00  ->  12:00 PM
    00:30  ->  12:30 AM
"""
from __future__ import annotations

from datetime import datetime, time

# strftime("%-I") / ("%#I") are platform-specific; lstrip("0") works on
# every platform and leaves "10", "11", "12" untouched.
_TIME_12 = "%I:%M:%S %p"
_TIME_12_SHORT = "%I:%M %p"


def clock_12h(value: datetime | time, seconds: bool = True) -> str:
    """Time of day only: datetime(2026, 9, 27, 20, 27, 28) -> '8:27:28 PM'."""
    fmt = _TIME_12 if seconds else _TIME_12_SHORT
    return value.strftime(fmt).lstrip("0")


def stamp_12h(value: datetime, seconds: bool = True) -> str:
    """Date plus 12-hour clock: datetime(...) -> '2026-09-27 8:27:28 PM'."""
    return f"{value:%Y-%m-%d} {clock_12h(value, seconds)}"
