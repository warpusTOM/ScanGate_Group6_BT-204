"""Global constants for IDCheck."""

APP_NAME = "IDCheck"
DB_PATH = "idcheck.db"

# ---- Gmail notification (OPTIONAL — needs internet + a Gmail App Password) ----
# 1. Enable 2-Step Verification on the sending Gmail account
# 2. Google Account > Security > App passwords > create one
# 3. Fill in below and set EMAIL_ENABLED = True
EMAIL_ENABLED = False
GMAIL_ADDRESS = ""          # e.g. school.notifier@gmail.com
GMAIL_APP_PASSWORD = ""     # 16-character App Password
EMAIL_SUBJECT = "ID Verification Notice"
