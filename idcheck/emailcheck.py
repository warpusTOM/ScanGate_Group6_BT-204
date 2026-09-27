"""Optional online email validation via EVA (free, no API key).

Picked from the public-apis list (github.com/public-apis/public-apis).
The core system stays offline; this only runs when you ask for it.
Anything network-related fails soft and returns None.
"""
from __future__ import annotations

import json
import urllib.parse
import urllib.request

EVA_URL = "https://eva.pingutil.com/email"


def check_email(address: str, timeout: float = 4.0) -> dict | None:
    """Ask EVA about one address.

    Returns {"deliverable": bool, "disposable": bool, "valid_syntax": bool},
    or None when offline / the API is down.
    """
    url = EVA_URL + "?" + urllib.parse.urlencode({"email": address})
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except Exception:
        return None
    data = payload.get("data") or {}
    return {
        "deliverable": bool(data.get("deliverable")),
        "disposable": bool(data.get("disposable")),
        "valid_syntax": bool(data.get("valid_syntax", True)),
    }
