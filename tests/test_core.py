import tempfile
import unittest
from pathlib import Path

from idcheck.importer import load_students_csv
from idcheck.models import Student
from idcheck.notifier import Notifier
from idcheck.system import IDCheckSystem

# header names here are the messy kind you get from real exports,
# the importer has to survive them
SAMPLE_CSV = (
    "Student No,Name,Email,Course,Grade,Section\n"
    "2026-0001,Juan Dela Cruz,juan@example.com,BT,2,BT-204\n"
    "2026-0002,Maria Santos,,BT,2,BT-204\n"
)


class FakeNotifier(Notifier):
    def __init__(self):
        self.sent = []

    def send(self, student, subject, body):
        self.sent.append((student.student_id, subject))
        return True


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

    def test_scan_logs_without_email(self):
        student, rec = self.system.scan("2026-0002")  # no gmail on file
        self.assertFalse(rec.emailed)
        self.assertEqual(self.system.db.count_scans(), 1)

    def test_scan_with_notifier_marks_emailed(self):
        fake = FakeNotifier()
        system = IDCheckSystem(self.db, notifier=fake)
        student, rec = system.scan("2026-0001")
        self.assertTrue(rec.emailed)
        self.assertEqual(fake.sent[0][0], "2026-0001")
        system.close()

    def test_scan_persists_after_reload(self):
        self.system.scan("2026-0001")
        fresh = IDCheckSystem(self.db)
        self.assertEqual(fresh.db.count_scans(), 1)
        self.assertEqual(fresh.db.count_students(), 2)
        fresh.close()

    def test_import_updates_existing(self):
        added, updated = self.system.import_students(self.csv)
        self.assertEqual((added, updated), (0, 2))


class TestWebApi(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        csv_path = Path(self.tmp.name) / "students.csv"
        csv_path.write_text(SAMPLE_CSV, encoding="utf-8")
        self.system = IDCheckSystem(Path(self.tmp.name) / "web.db")
        self.system.import_students(csv_path)
        from idcheck.web import create_app
        self.client = create_app(self.system).test_client()

    def tearDown(self):
        self.system.close()
        self.tmp.cleanup()

    def test_scan_found(self):
        r = self.client.post("/api/scan", json={"id": "2026-0001"})
        self.assertEqual(r.status_code, 200)
        data = r.get_json()
        self.assertTrue(data["found"])
        self.assertEqual(data["student"]["full_name"], "Juan Dela Cruz")

    def test_scan_not_found(self):
        r = self.client.post("/api/scan", json={"id": "NOPE-1"})
        self.assertFalse(r.get_json()["found"])

    def test_scan_empty_id_rejected(self):
        r = self.client.post("/api/scan", json={"id": "   "})
        self.assertEqual(r.status_code, 400)

    def test_stats_endpoint(self):
        self.client.post("/api/scan", json={"id": "2026-0002"})
        stats = self.client.get("/api/stats").get_json()
        self.assertEqual(stats["students"], 2)
        self.assertEqual(stats["scans_today"], 1)


class TestEmailCheck(unittest.TestCase):
    """EVA integration is mocked — no network in tests."""

    def test_parses_eva_response(self):
        import json
        import urllib.request

        import idcheck.emailcheck as ec

        class FakeResp:
            def read(self):
                return json.dumps({"status": "success", "data": {
                    "deliverable": True, "disposable": False,
                    "valid_syntax": True}}).encode()

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        orig = urllib.request.urlopen
        urllib.request.urlopen = lambda url, timeout=4: FakeResp()
        try:
            out = ec.check_email("someone@gmail.com")
        finally:
            urllib.request.urlopen = orig
        self.assertEqual(out["deliverable"], True)
        self.assertEqual(out["disposable"], False)

    def test_offline_returns_none(self):
        import urllib.request

        import idcheck.emailcheck as ec

        orig = urllib.request.urlopen

        def boom(url, timeout=4):
            raise OSError("no network")

        urllib.request.urlopen = boom
        try:
            self.assertIsNone(ec.check_email("someone@gmail.com"))
        finally:
            urllib.request.urlopen = orig


class TestThreadedServer(unittest.TestCase):
    """Regression: flask serves on worker threads, sqlite used to blow up
    there (ProgrammingError: objects created in a thread...)."""

    PORT = 5987

    def test_scan_over_real_http(self):
        import json
        import threading
        import time
        import urllib.request

        from idcheck.web import create_app

        tmp = tempfile.TemporaryDirectory()
        csv_path = Path(tmp.name) / "students.csv"
        csv_path.write_text(SAMPLE_CSV, encoding="utf-8")
        system = IDCheckSystem(Path(tmp.name) / "srv.db")
        system.import_students(csv_path)

        app = create_app(system)
        threading.Thread(
            target=lambda: app.run(port=self.PORT, threaded=True,
                                   use_reloader=False),
            daemon=True,
        ).start()

        base = f"http://127.0.0.1:{self.PORT}"
        up = False
        for _ in range(60):
            try:
                urllib.request.urlopen(base + "/api/stats", timeout=1)
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
        tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
