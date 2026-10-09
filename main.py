"""AttendGate entry point.

    python main.py              portal page (opens in a window or browser)
    python main.py --cli        terminal scanner instead
    python main.py --demo       load data/students_sample.csv first
    python main.py --port 8080  use another port
    python main.py --browser    force browser tab instead of a window

First run: open http://127.0.0.1:5000/login and you land on a one-time
setup page that creates the first admin account. More accounts can be
added later from the admin page.
"""
from __future__ import annotations

import argparse
import threading
import webbrowser

from idcheck.auth import UserStore, load_or_create_secret
from idcheck.constants import DB_PATH
from idcheck.system import IDCheckSystem

try:
    import webview  # pywebview, optional
    _HAVE_WEBVIEW = True
except ImportError:
    _HAVE_WEBVIEW = False


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="scangate",
        description="AttendGate - student attendance verifier (CSCQC)")
    parser.add_argument("--cli", action="store_true")
    parser.add_argument("--demo", action="store_true")
    parser.add_argument("--browser", action="store_true")
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--db", default=DB_PATH)
    args = parser.parse_args()

    system = IDCheckSystem(args.db)
    users = UserStore(args.db)
    if args.demo and system.stats()["students"] == 0:
        added, updated = system.import_students("data/students_sample.csv")
        print(f"demo data: {added} students imported")

    if args.cli:
        from idcheck.cli import run_cli
        run_cli(system)
        return

    from idcheck.web import create_app
    app = create_app(system, users, load_or_create_secret("data/secret.key"))
    url = f"http://127.0.0.1:{args.port}"

    if not users.has_users():
        print("no admin account yet - open the page and it will ask you "
              "to create one (first run setup)")

    if args.browser or not _HAVE_WEBVIEW:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
        print(f"AttendGate running at {url}  (Ctrl+C to stop)")
        app.run(port=args.port, threaded=True, use_reloader=False)
        return

    # desktop window via pywebview, flask runs in a thread behind it
    threading.Thread(
        target=lambda: app.run(port=args.port, threaded=True, use_reloader=False),
        daemon=True,
    ).start()
    webview.create_window("AttendGate - CSCQC Portal", url, width=1100, height=760)
    webview.start()


if __name__ == "__main__":
    main()
