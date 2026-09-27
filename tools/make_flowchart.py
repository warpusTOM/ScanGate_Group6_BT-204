"""Draw the ScanGate scan-flow algorithm chart -> docs/flowchart.png

    python tools/make_flowchart.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "flowchart.png"

W, H = 900, 1240
GREEN = "#198754"
RED = "#dc3545"
YELLOW = "#ffc107"
BLUE = "#0d6efd"
DARK = "#212529"
GREY = "#6c757d"

FONT_DIR = Path("C:/Windows/Fonts")
f_title = ImageFont.truetype(str(FONT_DIR / "arialbd.ttf"), 30)
f_box = ImageFont.truetype(str(FONT_DIR / "arial.ttf"), 17)
f_bold = ImageFont.truetype(str(FONT_DIR / "arialbd.ttf"), 18)
f_small = ImageFont.truetype(str(FONT_DIR / "arial.ttf"), 14)


def center_text(d, box, lines, font, fill=DARK):
    x0, y0, x1, y1 = box
    sizes = [d.textbbox((0, 0), t, font=font) for t in lines]
    total_h = sum(s[3] - s[1] for s in sizes) + 6 * (len(lines) - 1)
    y = y0 + (y1 - y0 - total_h) / 2
    for t, s in zip(lines, sizes):
        tw = s[2] - s[0]
        d.text((x0 + (x1 - x0 - tw) / 2, y), t, font=font, fill=fill)
        y += (s[3] - s[1]) + 6


def arrow(d, x0, y0, x1, y1, label=None, lx=0, ly=0):
    d.line([x0, y0, x1, y1], fill=DARK, width=3)
    import math
    ang = math.atan2(y1 - y0, x1 - x0)
    L = 12
    for a in (ang + 2.6, ang - 2.6):
        d.line([x1, y1, x1 + L * math.cos(a), y1 + L * math.sin(a)],
               fill=DARK, width=3)
    if label:
        d.text((lx, ly), label, font=f_bold, fill=GREY)


def box(d, b, lines, fill="#ffffff", outline=DARK, font=None, radius=14):
    d.rounded_rectangle(b, radius=radius, fill=fill, outline=outline, width=3)
    center_text(d, b, lines, font or f_box)


def diamond(d, cx, cy, w, h, lines):
    pts = [(cx, cy - h // 2), (cx + w // 2, cy), (cx, cy + h // 2),
           (cx - w // 2, cy)]
    d.polygon(pts, fill="#fff3cd", outline=DARK)
    d.line(pts + [pts[0]], fill=DARK, width=3)
    center_text(d, (cx - w // 2, cy - h // 2, cx + w // 2, cy + h // 2),
                lines, f_bold)


img = Image.new("RGB", (W, H), "#ffffff")
d = ImageDraw.Draw(img)

# title
center_text(d, (0, 18, W, 60), ["ScanGate - Scan & Verify Algorithm"], f_title)

cx = W // 2 - 100

# nodes
box(d, (cx, 80, cx + 200, 130), ["START"], fill="#e9ecef", radius=65)
box(d, (cx - 20, 170, cx + 220, 230), ["Open local database", "(scangate.db)"])
box(d, (cx - 20, 270, cx + 220, 330), ["Input / scan", "student ID number"])
diamond(d, cx + 100, 430, 240, 120, ["ID in database?"])
box(d, (cx - 20, 560, cx + 220, 640),
    ["Compute time note:", "EARLY / ON TIME / LATE", "vs class start time"])
box(d, (cx - 20, 690, cx + 220, 770),
    ["Show student card:", "full name, ID, section", "+ time note badge"])
box(d, (cx - 20, 820, cx + 220, 880), ["Save scan log", "(id, time, status, note)"])
box(d, (cx - 20, 930, cx + 220, 990), ["Ready for next scan"])

# not-registered branch (right side)
nx = cx + 460
box(d, (nx - 90, 395, nx + 90, 465), ["Show", "NOT REGISTERED"], fill="#f8d7da")

# arrows main flow
arrow(d, cx + 100, 130, cx + 100, 170)
arrow(d, cx + 100, 230, cx + 100, 270)
arrow(d, cx + 100, 330, cx + 100, 368)
arrow(d, cx + 100, 490, cx + 100, 560, "YES", cx + 118, 505)
arrow(d, cx + 100, 640, cx + 100, 690)
arrow(d, cx + 100, 770, cx + 100, 820)
arrow(d, cx + 100, 880, cx + 100, 930)

# NO branch: right to red box, then up and back to input
arrow(d, cx + 220, 430, nx - 90, 430, "NO", cx + 250, 402)
d.line([nx, 395, nx, 300], fill=DARK, width=3)
d.line([nx, 300, cx + 220, 300], fill=DARK, width=3)
arrow(d, cx + 220, 300, cx + 220, 300 - 1)  # tiny head at join

# loop-back from "ready" to input
d.line([cx, 960, 40, 960], fill=DARK, width=3)
d.line([40, 960, 40, 300], fill=DARK, width=3)
arrow(d, 40, 300, cx - 20, 300)

# legend
ly = 1040
d.text((60, ly), "Time notes:", font=f_bold, fill=DARK)
d.rounded_rectangle((60, ly + 28, 170, ly + 58), radius=29, fill=BLUE)
center_text(d, (60, ly + 28, 170, ly + 58), ["EARLY"], f_box, "#ffffff")
d.text((180, ly + 33), "before class start - 15 min", font=f_small, fill=DARK)
d.rounded_rectangle((60, ly + 68, 190, ly + 98), radius=29, fill=GREEN)
center_text(d, (60, ly + 68, 190, ly + 98), ["ON TIME"], f_box, "#ffffff")
d.text((200, ly + 73), "inside the window (start -15 to +10)", font=f_small, fill=DARK)
d.rounded_rectangle((60, ly + 108, 170, ly + 138), radius=29, fill=YELLOW)
center_text(d, (60, ly + 108, 170, ly + 138), ["LATE"], f_box, DARK)
d.text((180, ly + 113), "after class start + 10 min", font=f_small, fill=DARK)

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT)
print(f"flowchart -> {OUT}")
