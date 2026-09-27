"""Flask app serving the portal-style scanner page and the JSON API."""
from __future__ import annotations

from pathlib import Path

from flask import Flask, jsonify, render_template, request

from .system import IDCheckSystem

ROOT = Path(__file__).resolve().parent.parent


def create_app(system: IDCheckSystem) -> Flask:
    app = Flask("idcheck",
                template_folder=str(ROOT / "templates"),
                static_folder=str(ROOT / "static"))

    @app.get("/")
    def index():
        return render_template("index.html", stats=system.stats())

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
                "gmail": student.gmail,
                "course": student.course,
                "year_level": student.year_level,
                "section": student.section,
            },
            "scan": {
                "id": rec.id,
                "time": f"{rec.timestamp:%Y-%m-%d %H:%M:%S}",
                "emailed": rec.emailed,
                "note": rec.note,
            },
        })

    @app.post("/api/import")
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
    def api_students():
        return jsonify([s.__dict__ for s in system.db.all_students()])

    @app.get("/api/logs")
    def api_logs():
        try:
            n = int(request.args.get("n", 50))
        except ValueError:
            n = 50
        return jsonify([
            {"id": r.id, "student_id": r.student_id,
             "time": f"{r.timestamp:%Y-%m-%d %H:%M:%S}",
             "note": r.note, "emailed": r.emailed}
            for r in system.recent_scans(n)
        ])

    @app.get("/api/stats")
    def api_stats():
        return jsonify(system.stats())

    return app
