"""Flask app: public scan page for students, admin dashboard behind login."""
from __future__ import annotations

from pathlib import Path

from flask import (Flask, jsonify, redirect, render_template, request,
                   session, url_for)

from .auth import UserStore, admin_required
from .system import IDCheckSystem
from .timefmt import clock_12h, stamp_12h

ROOT = Path(__file__).resolve().parent.parent


def create_app(system: IDCheckSystem, users: UserStore,
               secret_key: str) -> Flask:
    app = Flask("idcheck",
                template_folder=str(ROOT / "templates"),
                static_folder=str(ROOT / "static"))
    app.secret_key = secret_key

    # ---------------- public: students only see this ----------------
    @app.get("/")
    def index():
        return render_template("index.html")

    @app.post("/api/scan")
    def api_scan():
        payload = request.get_json(silent=True) or {}
        sid = str(payload.get("id", "")).strip()
        note = str(payload.get("note", ""))
        if not sid:
            return jsonify({"found": False, "error": "empty id"}), 400
        try:
            student, rec = system.scan(sid, note)
        except KeyError:
            return jsonify({"found": False, "id": sid})
        return jsonify({
            "found": True,
            "student": {
                "student_id": student.student_id,
                "full_name": student.full_name,
                "course": student.course,
                "year_level": student.year_level,
                "section": student.section,
            },
            "scan": {
                "id": rec.id,
                "time": stamp_12h(rec.timestamp),
                "status": rec.status,
                "note": rec.note,
            },
        })

    # ---------------- auth ----------------
    @app.route("/setup", methods=["GET", "POST"])
    def setup():
        # first run only: no accounts yet, so anyone here makes the first one
        if users.has_users():
            return redirect(url_for("login"))
        error = None
        if request.method == "POST":
            u = request.form.get("username", "").strip()
            p = request.form.get("password", "")
            p2 = request.form.get("password2", "")
            if p != p2:
                error = "Passwords do not match."
            else:
                try:
                    users.create_user(u, p)
                except ValueError as exc:
                    error = str(exc)
                else:
                    session["admin"] = u
                    return redirect(url_for("admin"))
        return render_template("setup.html", error=error)

    @app.route("/login", methods=["GET", "POST"])
    def login():
        if not users.has_users():
            return redirect(url_for("setup"))
        error = None
        if request.method == "POST":
            u = request.form.get("username", "").strip()
            p = request.form.get("password", "")
            if users.verify(u, p):
                session["admin"] = u
                return redirect(url_for("admin"))
            error = "Wrong username or password."
        return render_template("login.html", error=error)

    @app.get("/logout")
    def logout():
        session.clear()
        return redirect(url_for("index"))

    # ---------------- admin only ----------------
    @app.get("/admin")
    @admin_required
    def admin():
        return render_template("admin.html", username=session.get("admin"))

    @app.post("/api/import")
    @admin_required
    def api_import():
        upload = request.files.get("file")
        if not upload or not upload.filename:
            return jsonify({"error": "no file uploaded"}), 400
        tmp = ROOT / "data" / "_upload_tmp.csv"
        upload.save(tmp)
        try:
            added, updated = system.import_students(tmp)
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        finally:
            tmp.unlink(missing_ok=True)
        return jsonify({"added": added, "updated": updated,
                        "students": system.db.count_students()})

    @app.get("/api/students")
    @admin_required
    def api_students():
        return jsonify([s.__dict__ for s in system.db.all_students()])

    @app.get("/api/logs")
    @admin_required
    def api_logs():
        try:
            n = int(request.args.get("n", 50))
        except ValueError:
            n = 50
        return jsonify([
            {"id": r.id, "student_id": r.student_id,
             "time": stamp_12h(r.timestamp),
             "note": r.note, "status": r.status}
            for r in system.recent_scans(n)
        ])

    @app.get("/api/settings")
    @admin_required
    def api_get_settings():
        start, early, late = system.time_window()
        # start_time stays HH:MM because it feeds <input type="time">;
        # start_time_12h is the human-readable echo next to it
        return jsonify({"start_time": f"{start:%H:%M}",
                        "start_time_12h": clock_12h(start, seconds=False),
                        "early_before": early, "late_after": late})

    @app.post("/api/settings")
    @admin_required
    def api_set_settings():
        data = request.get_json(silent=True) or {}
        try:
            system.set_time_window(
                str(data.get("start_time", "")),
                int(data.get("early_before", 0)),
                int(data.get("late_after", 0)))
        except (ValueError, TypeError):
            return jsonify({"error": "bad values (time HH:MM, minutes >= 0)"}), 400
        start, _, _ = system.time_window()
        return jsonify({"ok": True,
                        "start_time_12h": clock_12h(start, seconds=False)})

    @app.get("/api/stats")
    @admin_required
    def api_stats():
        return jsonify(system.stats())

    @app.post("/api/accounts")
    @admin_required
    def api_add_account():
        data = request.get_json(silent=True) or {}
        try:
            users.upsert_user(str(data.get("username", "")),
                              str(data.get("password", "")))
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        return jsonify({"ok": True, "users": users.list_users()})

    @app.post("/api/accounts/delete")
    @admin_required
    def api_delete_account():
        data = request.get_json(silent=True) or {}
        u = str(data.get("username", "")).strip()
        if u == session.get("admin"):
            return jsonify(
                {"error": "you cannot delete the account you are using"}), 400
        users.delete_user(u)
        return jsonify({"ok": True, "users": users.list_users()})

    return app
