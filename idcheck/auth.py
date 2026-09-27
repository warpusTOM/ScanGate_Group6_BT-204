"""Local admin accounts: salted PBKDF2 hashes in sqlite + flask sessions."""
from __future__ import annotations

import functools
import hashlib
import hmac
import os
import sqlite3
import threading
from pathlib import Path

from flask import jsonify, redirect, request, session, url_for

USERS_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    username      TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'admin'
);
"""

_ITERATIONS = 120_000


def _hash(password: str, salt: bytes | None = None) -> str:
    salt = salt or os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, _ITERATIONS)
    return f"{salt.hex()}${digest.hex()}"


def _check(password: str, stored: str) -> bool:
    salt_hex, digest_hex = stored.split("$", 1)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(),
                                 bytes.fromhex(salt_hex), _ITERATIONS)
    return hmac.compare_digest(digest.hex(), digest_hex)


def load_or_create_secret(path: str | Path) -> str:
    """Flask session signing key, generated once per install."""
    p = Path(path)
    if p.exists():
        return p.read_text(encoding="utf-8").strip()
    p.parent.mkdir(parents=True, exist_ok=True)
    secret = os.urandom(32).hex()
    p.write_text(secret, encoding="utf-8")
    return secret


class UserStore:
    """Admin accounts. Shares the sqlite file with the main Database."""

    def __init__(self, db_path: str | Path):
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        with self._lock:
            self._conn.executescript(USERS_SCHEMA)
            self._conn.commit()

    def has_users(self) -> bool:
        with self._lock:
            return self._conn.execute(
                "SELECT COUNT(*) FROM users").fetchone()[0] > 0

    def list_users(self) -> list[str]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT username FROM users ORDER BY username").fetchall()
        return [r[0] for r in rows]

    def create_user(self, username: str, password: str) -> None:
        username = username.strip()
        if len(username) < 3:
            raise ValueError("username needs at least 3 characters")
        if len(password) < 4:
            raise ValueError("password needs at least 4 characters")
        with self._lock:
            try:
                self._conn.execute(
                    "INSERT INTO users (username, password_hash) VALUES (?, ?)",
                    (username, _hash(password)))
                self._conn.commit()
            except sqlite3.IntegrityError:
                raise ValueError(f"username '{username}' is taken") from None

    def upsert_user(self, username: str, password: str) -> None:
        """Add an account, or reset the password if it exists."""
        username = username.strip()
        if len(username) < 3:
            raise ValueError("username needs at least 3 characters")
        if len(password) < 4:
            raise ValueError("password needs at least 4 characters")
        with self._lock:
            self._conn.execute(
                """INSERT INTO users (username, password_hash) VALUES (?, ?)
                   ON CONFLICT(username) DO UPDATE SET
                       password_hash=excluded.password_hash""",
                (username, _hash(password)))
            self._conn.commit()

    def verify(self, username: str, password: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT password_hash FROM users WHERE username = ?",
                (username.strip(),)).fetchone()
        if row is None:
            return False
        return _check(password, row[0])

    def delete_user(self, username: str) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM users WHERE username = ?",
                               (username,))
            self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()


def admin_required(view):
    """Page routes bounce to /login; API routes get a 401."""
    @functools.wraps(view)
    def wrapped(*args, **kwargs):
        if not session.get("admin"):
            if request.path.startswith("/api/"):
                return jsonify({"error": "login required"}), 401
            return redirect(url_for("login"))
        return view(*args, **kwargs)
    return wrapped
