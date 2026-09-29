import io
import tempfile
import unittest
from datetime import datetime, time
from pathlib import Path

from idcheck.auth import UserStore
from idcheck.importer import load_students_csv
from idcheck.models import Student
from idcheck.system import IDCheckSystem
from idcheck.timefmt import clock_12h, stamp_12h

# header names here are the messy kind you get from real exports,
# the importer has to survive them
SAMPLE_CSV = (
    "Student No,Name,Email,Course,Grade,Section\n"
    "2026-0001,Juan Dela Cruz,juan@example.com,BT,2,BT-204\n"
    "2026-0002,Maria Santos,,BT,2,BT-204\n"
)


def make_app(system, users):
    from idcheck.web import create_app
    return create_app(system, users, secret_key="test-secret")


class TestStudent(unittest.TestCase):
    def test_requires_id_and_name(self):
        with self.assertRaises(ValueError):
            Student("", "Juan")
        with self.assertRaises(ValueError):
            Student("2026-0001", "   ")

    def test_bad_gmail_rejected(self):
        with self.assertRaises(ValueError):
            Student("2026-0001", "Juan", gmail="not-an-email")

    def test_year_section(self):
        s = Student("2026-0001", "Juan", course="BT", year_level="2",
                    section="BT-204")
        self.assertEqual(s.year_section, "BT 2 BT-204")


class TestImporter(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.csv = Path(self.tmp.name) / "students.csv"
        self.csv.write_text(SAMPLE_CSV, encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_alias_headers(self):
        students = load_students_csv(self.csv)
        self.assertEqual(len(students), 2)
        self.assertEqual(students[0].student_id, "2026-0001")
        self.assertEqual(students[0].section, "BT-204")

    def test_missing_required_column(self):
        bad = Path(self.tmp.name) / "bad.csv"
        bad.write_text("foo,bar\n1,2\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            load_students_csv(bad)

    def test_portal_export_format_directly(self):
        """The school portal's raw credentials export must import as-is."""
        raw = Path(self.tmp.name) / "portal.csv"
        raw.write_text(
            "section,student_no,surname,first_name,access_key\n"
            "BT-204,20253152,MOLINO,JHON LLOYD,MOLINO20253152\n"
            "BT-104,,Alimen,Mark ian B.,ALIMEN\n",
            encoding="utf-8")
        students = load_students_csv(raw)
        self.assertEqual(len(students), 2)

        molino = students[0]
        self.assertEqual(molino.student_id, "20253152")
        self.assertEqual(molino.full_name, "Jhon Lloyd Molino")
        self.assertEqual(molino.section, "BT-204")
        self.assertEqual(molino.course, "BT")
        self.assertEqual(molino.year_level, "2")

        # blank student number falls back to the access key
        alimen = students[1]
        self.assertEqual(alimen.student_id, "ALIMEN")
        self.assertEqual(alimen.full_name, "Mark Ian B. Alimen")

    def test_portal_format_skips_unidentifiable_rows(self):
        raw = Path(self.tmp.name) / "portal2.csv"
        raw.write_text(
            "section,student_no,surname,first_name,access_key\n"
            "BT-104,,Ghost,Nobody,\n"
            "BT-104,20263748,Acaso,Kurt Andrei M.,ACASO20263748\n",
            encoding="utf-8")
        students = load_students_csv(raw)
        self.assertEqual(len(students), 1)


class TestUserStore(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.users = UserStore(Path(self.tmp.name) / "u.db")

    def tearDown(self):
        self.users.close()
        self.tmp.cleanup()

    def test_create_and_verify(self):
        self.users.create_user("molino", "20253152")
        self.assertTrue(self.users.verify("molino", "20253152"))
        self.assertFalse(self.users.verify("molino", "wrong"))
        self.assertFalse(self.users.verify("ghost", "20253152"))

    def test_password_not_stored_plain(self):
        self.users.create_user("molino", "20253152")
        row = self.users._conn.execute(
            "SELECT password_hash FROM users WHERE username='molino'").fetchone()
        self.assertNotIn("20253152", row[0])

    def test_duplicate_username_raises(self):
        self.users.create_user("molino", "20253152")
        with self.assertRaises(ValueError):
            self.users.create_user("molino", "other")

    def test_upsert_resets_password(self):
        self.users.create_user("molino", "20253152")
        self.users.upsert_user("molino", "newpass")
        self.assertFalse(self.users.verify("molino", "20253152"))
        self.assertTrue(self.users.verify("molino", "newpass"))

    def test_short_inputs_rejected(self):
        with self.assertRaises(ValueError):
            self.users.create_user("ab", "password")
        with self.assertRaises(ValueError):
            self.users.create_user("molino", "abc")

    def test_delete_user(self):
        self.users.create_user("molino", "20253152")
        self.users.delete_user("molino")
        self.assertFalse(self.users.has_users())


class TestSystem(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "t.db"
        self.csv = Path(self.tmp.name) / "students.csv"
        self.csv.write_text(SAMPLE_CSV, encoding="utf-8")
        self.system = IDCheckSystem(self.db)
        self.system.import_students(self.csv)

    def tearDown(self):
        self.system.close()
        self.tmp.cleanup()

    def test_lookup_miss_raises(self):
        with self.assertRaises(KeyError):
            self.system.lookup("XXXX-9999")

    def test_scan_logs_status(self):
        student, rec = self.system.scan("2026-0002")
        self.assertIn(rec.status, ("EARLY", "ON TIME", "LATE"))
        self.assertEqual(self.system.db.count_scans(), 1)

    def test_scan_persists_after_reload(self):
        self.system.scan("2026-0001")
        fresh = IDCheckSystem(self.db)
        self.assertEqual(fresh.db.count_scans(), 1)
        self.assertEqual(fresh.db.count_students(), 2)
        fresh.close()

    def test_import_updates_existing(self):
        added, updated = self.system.import_students(self.csv)
        self.assertEqual((added, updated), (0, 2))


class TestTimeWindow(unittest.TestCase):
    """class at 08:00, on-time window 07:45-08:10 by default."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "t.db"
        csv_path = Path(self.tmp.name) / "students.csv"
        csv_path.write_text(SAMPLE_CSV, encoding="utf-8")
        self.system = IDCheckSystem(self.db)
        self.system.import_students(csv_path)

    def tearDown(self):
        self.system.close()
        self.tmp.cleanup()

    def _scan_at(self, hh, mm):
        _, rec = self.system.scan(
            "2026-0001", when=datetime(2026, 9, 27, hh, mm))
        return rec.status

    def test_early(self):
        self.assertEqual(self._scan_at(7, 30), "EARLY")

    def test_window_edges(self):
        self.assertEqual(self._scan_at(7, 45), "ON TIME")
        self.assertEqual(self._scan_at(8, 0), "ON TIME")
        self.assertEqual(self._scan_at(8, 10), "ON TIME")

    def test_late(self):
        self.assertEqual(self._scan_at(8, 11), "LATE")

    def test_custom_window(self):
        self.system.set_time_window("13:30", 20, 5)
        _, rec = self.system.scan(
            "2026-0001", when=datetime(2026, 9, 27, 13, 36))
        self.assertEqual(rec.status, "LATE")
        # survives reload
        fresh = IDCheckSystem(self.db)
        self.assertEqual(fresh.time_window()[0].hour, 13)
        fresh.close()

    def test_bad_time_rejected(self):
        with self.assertRaises(ValueError):
            self.system.set_time_window("25:99", 10, 10)


class TestClockFormat(unittest.TestCase):
    """Displayed times are 12-hour with AM/PM; the database stays 24-hour."""

    def test_afternoon(self):
        self.assertEqual(clock_12h(datetime(2026, 9, 27, 20, 27, 28)),
                         "8:27:28 PM")

    def test_morning_drops_the_leading_zero(self):
        self.assertEqual(clock_12h(datetime(2026, 9, 27, 8, 5, 3)),
                         "8:05:03 AM")

    def test_noon_and_midnight(self):
        self.assertEqual(clock_12h(datetime(2026, 9, 27, 12, 0, 0)),
                         "12:00:00 PM")
        self.assertEqual(clock_12h(datetime(2026, 9, 27, 0, 30, 0)),
                         "12:30:00 AM")

    def test_ten_and_eleven_keep_both_digits(self):
        self.assertEqual(clock_12h(datetime(2026, 9, 27, 10, 15, 0)),
                         "10:15:00 AM")
        self.assertEqual(clock_12h(datetime(2026, 9, 27, 23, 59, 59)),
                         "11:59:59 PM")

    def test_short_form_for_class_start(self):
        self.assertEqual(clock_12h(time(8, 0), seconds=False), "8:00 AM")
        self.assertEqual(clock_12h(time(13, 30), seconds=False), "1:30 PM")

    def test_stamp_keeps_the_date(self):
        self.assertEqual(stamp_12h(datetime(2026, 9, 27, 20, 27, 28)),
                         "2026-09-27 8:27:28 PM")

    def test_record_str_is_12_hour(self):
        from idcheck.models import ScanRecord
        rec = ScanRecord("2026-0001", datetime(2026, 9, 27, 20, 27, 28),
                         status="LATE", id=1)
        self.assertIn("8:27:28 PM", str(rec))
        self.assertNotIn("20:27", str(rec))

    def test_storage_column_stays_24_hour(self):
        """12-hour in the db would break date slicing and sorting."""
        tmp = tempfile.TemporaryDirectory()
        system = IDCheckSystem(Path(tmp.name) / "t.db")
        system.db.upsert_student(Student("2026-0001", "Juan"))
        system.scan("2026-0001", when=datetime(2026, 9, 27, 20, 27))
        raw = system.db._conn.execute(
            "SELECT timestamp FROM scan_logs").fetchone()[0]
        self.assertTrue(raw.startswith("2026-09-27T20:27"), raw)
        system.close()
        tmp.cleanup()


class TestWebApi(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        csv_path = Path(self.tmp.name) / "students.csv"
        csv_path.write_text(SAMPLE_CSV, encoding="utf-8")
        db_path = Path(self.tmp.name) / "web.db"
        self.system = IDCheckSystem(db_path)
        self.system.import_students(csv_path)
        self.users = UserStore(db_path)
        self.users.create_user("molino", "20253152")
        self.client = make_app(self.system, self.users).test_client()

    def tearDown(self):
        self.system.close()
        self.users.close()
        self.tmp.cleanup()

    def login(self):
        return self.client.post(
            "/login", data={"username": "molino", "password": "20253152"})

    # ---- public ----
    def test_scan_found_public(self):
        r = self.client.post("/api/scan", json={"id": "2026-0001"})
        self.assertEqual(r.status_code, 200)
        data = r.get_json()
        self.assertTrue(data["found"])
        self.assertEqual(data["student"]["full_name"], "Juan Dela Cruz")
        self.assertIn(data["scan"]["status"], ("EARLY", "ON TIME", "LATE"))
        self.assertEqual(data["student"]["section"], "BT-204")

    def test_scan_not_found_public(self):
        r = self.client.post("/api/scan", json={"id": "NOPE-1"})
        self.assertFalse(r.get_json()["found"])

    def test_scan_empty_id_rejected(self):
        r = self.client.post("/api/scan", json={"id": "   "})
        self.assertEqual(r.status_code, 400)

    # ---- 12-hour clock ----
    def test_scan_time_is_12_hour(self):
        r = self.client.post("/api/scan", json={"id": "2026-0001"})
        self.assertRegex(r.get_json()["scan"]["time"],
                         r"^\d{4}-\d{2}-\d{2} \d{1,2}:\d{2}:\d{2} (AM|PM)$")

    def test_log_time_is_12_hour(self):
        self.client.post("/api/scan", json={"id": "2026-0001"})
        self.login()
        logs = self.client.get("/api/logs").get_json()
        self.assertRegex(logs[0]["time"],
                         r"^\d{4}-\d{2}-\d{2} \d{1,2}:\d{2}:\d{2} (AM|PM)$")

    def test_settings_expose_a_12_hour_echo(self):
        self.login()
        self.client.post("/api/settings",
                         json={"start_time": "13:30", "early_before": 15,
                               "late_after": 10})
        s = self.client.get("/api/settings").get_json()
        self.assertEqual(s["start_time"], "13:30")   # the control stays 24h
        self.assertEqual(s["start_time_12h"], "1:30 PM")

    def test_public_page_has_no_import(self):
        html = self.client.get("/").get_data(as_text=True)
        self.assertIn("Student ID Verification", html)
        self.assertNotIn("csvFile", html)  # import lives in admin only

    # ---- admin gating ----
    def test_admin_redirects_when_logged_out(self):
        r = self.client.get("/admin")
        self.assertEqual(r.status_code, 302)
        self.assertIn("/login", r.headers["Location"])

    def test_apis_blocked_when_logged_out(self):
        for path in ["/api/students", "/api/logs", "/api/stats"]:
            r = self.client.get(path)
            self.assertEqual(r.status_code, 401, path)
        r = self.client.post("/api/import")
        self.assertEqual(r.status_code, 401)

    def test_wrong_password_rejected(self):
        self.client.post("/login",
                         data={"username": "molino", "password": "nope"})
        r = self.client.get("/api/stats")
        self.assertEqual(r.status_code, 401)

    def test_login_unlocks_admin(self):
        r = self.login()
        self.assertEqual(r.status_code, 302)  # redirect to /admin
        stats = self.client.get("/api/stats").get_json()
        self.assertEqual(stats["students"], 2)

    def test_import_after_login(self):
        self.login()
        r = self.client.post(
            "/api/import",
            data={"file": (io.BytesIO(SAMPLE_CSV.encode()), "students.csv")},
            content_type="multipart/form-data")
        data = r.get_json()
        self.assertEqual(data["added"], 0)
        self.assertEqual(data["updated"], 2)

    def test_add_and_delete_account(self):
        self.login()
        r = self.client.post("/api/accounts",
                             json={"username": "guard1", "password": "gate123"})
        self.assertIn("guard1", r.get_json()["users"])
        r = self.client.post("/api/accounts/delete",
                             json={"username": "guard1"})
        self.assertNotIn("guard1", r.get_json()["users"])

    def test_cannot_delete_self(self):
        self.login()
        r = self.client.post("/api/accounts/delete",
                             json={"username": "molino"})
        self.assertEqual(r.status_code, 400)


class TestFirstRunSetup(unittest.TestCase):
    def test_setup_creates_first_admin(self):
        tmp = tempfile.TemporaryDirectory()
        db_path = Path(tmp.name) / "fresh.db"
        system = IDCheckSystem(db_path)
        users = UserStore(db_path)
        client = make_app(system, users).test_client()

        # no accounts: /login should bounce to /setup
        r = client.get("/login")
        self.assertIn("/setup", r.headers["Location"])

        r = client.post("/setup", data={
            "username": "molino", "password": "20253152",
            "password2": "20253152"})
        self.assertEqual(r.status_code, 302)
        self.assertTrue(users.verify("molino", "20253152"))

        # setup closes after the first account exists
        r = client.get("/setup")
        self.assertEqual(r.status_code, 302)
        self.assertIn("/login", r.headers["Location"])

        system.close()
        users.close()
        tmp.cleanup()


class TestThreadedServer(unittest.TestCase):
    """Regression: flask serves on worker threads, sqlite used to blow up
    there (ProgrammingError: objects created in a thread...)."""

    PORT = 5987

    def test_scan_over_real_http(self):
        import json
        import threading
        import time
        import urllib.request

        tmp = tempfile.TemporaryDirectory()
        csv_path = Path(tmp.name) / "students.csv"
        csv_path.write_text(SAMPLE_CSV, encoding="utf-8")
        db_path = Path(tmp.name) / "srv.db"
        system = IDCheckSystem(db_path)
        system.import_students(csv_path)
        users = UserStore(db_path)

        app = make_app(system, users)
        threading.Thread(
            target=lambda: app.run(port=self.PORT, threaded=True,
                                   use_reloader=False),
            daemon=True,
        ).start()

        base = f"http://127.0.0.1:{self.PORT}"
        up = False
        for _ in range(60):
            try:
                urllib.request.urlopen(base + "/", timeout=1)
                up = True
                break
            except Exception:
                time.sleep(0.1)
        self.assertTrue(up, "server never came up")

        req = urllib.request.Request(
            base + "/api/scan",
            data=json.dumps({"id": "2026-0001"}).encode(),
            headers={"Content-Type": "application/json"})
        data = json.loads(urllib.request.urlopen(req, timeout=5).read())
        self.assertTrue(data["found"])
        self.assertEqual(data["student"]["full_name"], "Juan Dela Cruz")

        page = urllib.request.urlopen(base + "/", timeout=5).read().decode()
        self.assertIn("Student ID Verification", page)

        system.close()
        users.close()
        tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
