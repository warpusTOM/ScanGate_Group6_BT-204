"""Draw a preview of the home screen, so the design can be checked without a phone.

This is not the real layout. It is a drawing made from the same numbers the
layout XML uses (the padding, the font sizes, the colours, the same seal and
the same banner), which is enough to answer "does this look like the school"
before going to the trouble of installing the APK.

    python tools/make_screen_preview.py

Writes dist/home-screen-preview.png at 1080x1920, which is a 5.5 inch phone
at xxhdpi. Needs Pillow, so run it with the environment that has it:

    ~/.workbuddy-ai/binaries/python/envs/stockwise311/Scripts/python.exe
"""
from __future__ import annotations

from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    raise SystemExit("Pillow is not installed for this Python. Use the 3.11 "
                     "environment named in the docstring.")

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
OUT = ROOT / "dist" / "home-screen-preview.png"

SCALE = 3          # xxhdpi: 1dp = 3px
WIDTH = 360 * SCALE
HEIGHT = 640 * SCALE

FONT_DIR = Path(r"C:\Windows\Fonts")

GREEN = (25, 135, 84)
GREEN_DARK = (20, 108, 67)
WHITE = (255, 255, 255)
TEXT_DARK = (33, 37, 41)
TEXT_MUTED = (73, 80, 87)
TEXT_ON_DARK = (176, 190, 197)
PALE_GREEN = (200, 230, 201)
BLUE = (13, 110, 253)
AMBER = (255, 193, 7)
GREY_LINE = (60, 80, 92)


def dp(value: float) -> int:
    return int(value * SCALE)


def font(name: str, size_dp: float) -> ImageFont.FreeTypeFont:
    for candidate in (name, "arial.ttf"):
        path = FONT_DIR / candidate
        if path.exists():
            return ImageFont.truetype(str(path), dp(size_dp))
    return ImageFont.load_default()


BOLD = font("arialbd.ttf", 16)
BOLD_LARGE = font("arialbd.ttf", 22)
BOLD_SMALL = font("arialbd.ttf", 12)
REGULAR = font("arial.ttf", 14)
SMALL = font("arial.ttf", 11)
TINY = font("arial.ttf", 12)


def centre_text(draw, text, fnt, y, colour, box=None):
    """Draws text centred. With no box it centres on the screen, which is what
    the full-width buttons want; with a box it centres inside that box, which
    is what the little status chips want."""
    left_edge, right_edge = (0, WIDTH) if box is None else box
    left, top, right, bottom = draw.textbbox((0, 0), text, font=fnt)
    x = left_edge + (right_edge - left_edge - (right - left)) / 2 - left
    draw.text((x, y), text, font=fnt, fill=colour)


def rounded(draw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def build() -> Image.Image:
    screen = Image.new("RGB", (WIDTH, HEIGHT), (26, 42, 51))

    # 1. the school banner, cropped the way centerCrop would
    banner = Image.open(RES / "drawable-nodpi" / "school_banner.jpg").convert("RGB")
    ratio = max(WIDTH / banner.width, HEIGHT / banner.height)
    banner = banner.resize((int(banner.width * ratio), int(banner.height * ratio)),
                           Image.LANCZOS)
    screen.paste(banner, ((WIDTH - banner.width) // 2, (HEIGHT - banner.height) // 2))

    # 2. the dark wash, 70% at the top to 80% at the bottom
    wash = Image.new("RGBA", (WIDTH, HEIGHT))
    wash_draw = ImageDraw.Draw(wash)
    for y in range(HEIGHT):
        alpha = int(179 + (204 - 179) * y / HEIGHT)
        wash_draw.line([(0, y), (WIDTH, y)], fill=(0, 0, 0, alpha))
    screen = Image.alpha_composite(screen.convert("RGBA"), wash).convert("RGB")

    draw = ImageDraw.Draw(screen)
    pad = dp(16)

    # 3. header
    seal = Image.open(RES / "drawable-nodpi" / "school_logo.png").convert("RGBA")
    seal = seal.resize((dp(58), dp(58)), Image.LANCZOS)
    screen.paste(seal, (pad, pad), seal)

    draw.text((pad + dp(70), pad + dp(6)), "SCANGATE", font=BOLD_LARGE, fill=WHITE)
    draw.text((pad + dp(70), pad + dp(38)), "College of St. Catherine",
              font=SMALL, fill=PALE_GREEN)
    draw.text((pad + dp(70), pad + dp(52)), "Quezon City", font=SMALL, fill=PALE_GREEN)

    y = pad + dp(58) + dp(18)

    # 4. the camera button
    rounded(draw, [pad, y, WIDTH - pad, y + dp(52)], dp(8), GREEN)
    centre_text(draw, "SCAN ID WITH CAMERA", BOLD, y + dp(16), WHITE)
    y += dp(52) + dp(10)

    # 5. the typing box
    rounded(draw, [pad, y, WIDTH - pad, y + dp(46)], dp(8), WHITE,
            outline=(206, 212, 218))
    draw.text((pad + dp(12), y + dp(15)), "Student number (e.g. 20253152)",
              font=REGULAR, fill=(108, 117, 125))
    y += dp(46) + dp(8)

    # 6. verify button
    rounded(draw, [pad, y, WIDTH - pad, y + dp(42)], dp(8), None,
            outline=(144, 164, 174))
    centre_text(draw, "VERIFY TYPED NUMBER", REGULAR, y + dp(13), (236, 239, 241))
    y += dp(42) + dp(16)

    # 7. result card, shown here with a student in it
    card_top = y
    card_bottom = y + dp(128)
    rounded(draw, [pad, card_top, WIDTH - pad, card_bottom], dp(10), WHITE)
    draw.text((pad + dp(16), card_top + dp(14)), "Jhon Lloyd Molino",
              font=BOLD_LARGE, fill=TEXT_DARK)
    draw.text((pad + dp(16), card_top + dp(46)), "ID: 20253152",
              font=REGULAR, fill=TEXT_MUTED)
    draw.text((pad + dp(16), card_top + dp(64)), "Section: BT-204",
              font=REGULAR, fill=TEXT_MUTED)
    chip_top = card_top + dp(86)
    rounded(draw, [pad + dp(16), chip_top, WIDTH - pad - dp(16), chip_top + dp(28)],
            dp(14), GREEN)
    centre_text(draw, "ON TIME", BOLD, chip_top + dp(6), WHITE)
    y = card_bottom + dp(14)

    # 8. class time link and stats
    draw.text((pad, y), "Class time", font=TINY, fill=(128, 203, 196))
    y += dp(22)
    draw.text((pad, y), "262 students  |  today: 12 scans (2 late)  |  class 08:00",
              font=TINY, fill=TEXT_ON_DARK)
    y += dp(24)

    # 9. recent scans
    draw.text((pad, y), "Recent scans", font=BOLD_SMALL, fill=WHITE)
    y += dp(20)

    rows = [
        ("8:27:28 PM", "20253152", "LATE", AMBER, (33, 37, 41)),
        ("8:11:02 PM", "20263748", "ON TIME", GREEN, WHITE),
        ("7:44:55 PM", "ALIMEN", "EARLY", BLUE, WHITE),
        ("7:40:10 PM", "20263649", "EARLY", BLUE, WHITE),
    ]
    chip_font = font("arialbd.ttf", 11)
    for time_text, student_id, status, colour, text_colour in rows:
        draw.text((pad, y), time_text, font=REGULAR, fill=(236, 239, 241))
        draw.text((pad, y + dp(17)), student_id, font=SMALL, fill=TEXT_ON_DARK)
        chip_left = WIDTH - pad - dp(72)
        chip_right = WIDTH - pad
        rounded(draw, [chip_left, y + dp(6), chip_right, y + dp(28)],
                dp(11), colour)
        centre_text(draw, status, chip_font, y + dp(10), text_colour,
                    box=(chip_left, chip_right))
        y += dp(40)
        draw.line([(pad, y - dp(6)), (WIDTH - pad, y - dp(6))], fill=GREY_LINE)

    return screen


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    build().save(OUT)
    print(f"preview -> {OUT}  ({OUT.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
