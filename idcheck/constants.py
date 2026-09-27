"""Global constants for ScanGate."""

APP_NAME = "ScanGate"
DB_PATH = "scangate.db"

# time-window defaults for the scan note (admins can change them on /admin)
# EARLY: scanned before start - early_before
# ON TIME: inside [start - early_before, start + late_after]
# LATE: after start + late_after
DEFAULT_START_TIME = "08:00"
EARLY_BEFORE_MINUTES = 15
LATE_AFTER_MINUTES = 10
