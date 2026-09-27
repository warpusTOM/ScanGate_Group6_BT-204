"""Generate the IDCheck icon (assets/icon.ico).

    python tools/make_icon.py

Simple badge: dark teal rounded square, white ID card outline,
green scan line across it.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets" / "icon.ico"


def make_icon(path: Path) -> None:
    s = 256
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    d.rounded_rectangle([8, 8, s - 8, s - 8], radius=48, fill="#0f4c5c")

    # id card
    d.rounded_rectangle([48, 76, 208, 180], radius=12, fill="#f8f9fa")
    d.ellipse([66, 102, 98, 134], fill="#0f4c5c")          # photo circle
    d.rounded_rectangle([110, 104, 188, 114], radius=4, fill="#6c757d")
    d.rounded_rectangle([110, 124, 172, 134], radius=4, fill="#adb5bd")
    d.rounded_rectangle([66, 148, 188, 160], radius=4, fill="#adb5bd")

    # scan line
    d.rectangle([36, 118, 220, 126], fill="#7fe08c")

    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, sizes=[(16, 16), (32, 32), (48, 48), (64, 64),
                          (128, 128), (256, 256)])
    print(f"icon written -> {path}")


if __name__ == "__main__":
    make_icon(OUT)
