"""IDCheck entry point.

    python main.py              portal page (opens in a window or browser)
    python main.py --cli        terminal scanner instead
    python main.py --demo       load data/students_sample.csv first
    python main.py --port 8080  use another port
    python main.py --browser    force browser tab instead of a window
"""
from __future__ import annotations

import argparse
import threading
import webbrowser

from idcheck.constants import DB_PATH
from idcheck.system import IDCheckSystem

try:
    import webview  # pywebview, optional
    _HAVE_WEBVIEW = True
except ImportError:
    _HAVE_WEBVIEW = False


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="idcheck",
        description="IDCheck - student ID scanner and verifier (CSCQC)")
    parser.add_argument("--cli", action="store_true")
    parser.add_argument("--demo", action="store_true")
    parser.add_argument("--browser", action="store_true")
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--db", default=DB_PATH)
    args = parser.parse_args()

    system = IDCheckSystem(args.db)
    if args.demo and system.stats()["students"] == 0:
        added, updated = system.import_students("data/students_sample.csv")
        print(f"demo data: {added} students imported")

    if args.cli:
        from idcheck.cli import run_cli
        run_cli(system)
        return

    from idcheck.web import create_app
    app = create_app(system)
    url = f"http://127.0.0.1:{args.port}"

    if args.browser or not _HAVE_WEBVIEW:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
        print(f"IDCheck running at {url}  (Ctrl+C to stop)")
        app.run(port=args.port, threaded=True, use_reloader=False)
        return

    # desktop window via pywebview, flask runs in a thread behind it
    threading.Thread(
        target=lambda: app.run(port=args.port, threaded=True, use_reloader=False),
        daemon=True,
    ).start()
    webview.create_window("IDCheck - CSCQC Portal", url, width=1100, height=760)
    webview.start()


if __name__ == "__main__":
    main()
