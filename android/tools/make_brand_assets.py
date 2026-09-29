"""Build the app's branding artwork out of the school's own images.

The laptop portal already uses the school's seal and banner. The phone app
should look like the same system, so instead of drawing anything new this
takes the two files the portal uses and produces the sizes Android needs.

    python tools/make_brand_assets.py

Inputs (from the repo root, not from this folder):

    ../static/img/cscqcph.png    the CSCQC seal, 1016x1011, transparent
    ../static/img/stcat.png      the school banner, 1906x1185

Outputs, all inside app/src/main/res:

    mipmap-mdpi/ic_launcher.png          48x48
    mipmap-hdpi/ic_launcher.png          72x72
    mipmap-xhdpi/ic_launcher.png         96x96
    mipmap-xxhdpi/ic_launcher.png       144x144
    mipmap-xxxhdpi/ic_launcher.png      192x192
    mipmap-anydpi-v26/ic_launcher.xml    adaptive icon for Android 8 and up
    mipmap-anydpi-v26/ic_launcher_round.xml
    drawable-nodpi/ic_launcher_foreground.png   the seal on a 108dp canvas
    drawable-nodpi/school_logo.png        the seal for the home screen header
    drawable-nodpi/school_banner.jpg      the banner, shrunk for a phone

Needs Pillow. The bench environment that has it is the 3.11 one:

    ~/.workbuddy-ai/binaries/python/envs/stockwise311/Scripts/python.exe

The generated files are committed, so this only needs running again if the
school changes its logo or the sizes need adjusting.
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    raise SystemExit(
        "Pillow is not installed for this Python.\n"
        "Use the environment that has it:\n"
        "  ~/.workbuddy-ai/binaries/python/envs/stockwise311/Scripts/python.exe "
        "tools/make_brand_assets.py")

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
RES = ROOT / "app" / "src" / "main" / "res"

SEAL = REPO / "static" / "img" / "cscqcph.png"
BANNER = REPO / "static" / "img" / "stcat.png"

# Android launcher icons are 48dp. Each folder holds one density.
LAUNCHER_DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
LAUNCHER_DP = 48

# An adaptive icon is drawn on a 108dp canvas, but the launcher only ever
# shows the middle part. The safe zone is 66dp, so the seal is sized to that
# and never gets clipped, whatever shape mask the phone uses.
ADAPTIVE_CANVAS_DP = 108
ADAPTIVE_SAFE_DP = 66
ADAPTIVE_CANVAS_PX = 432          # 108dp at xxxhdpi

HEADER_LOGO_PX = 192              # a 64dp logo at xxxhdpi
BANNER_WIDTH_PX = 1080            # phone width; taller is wasted memory


def load(path: Path) -> Image.Image:
    if not path.exists():
        raise SystemExit(f"missing source image: {path}")
    return Image.open(path).convert("RGBA")


def save_png(image: Image.Image, path: Path, colors: int = 256) -> None:
    """Saves a PNG as a palette image.

    The seal is flat artwork with a few gradients, not a photograph, so 256
    colours look identical to true colour and cost a fraction of the bytes.
    A full colour 192px seal is 75 KB; the same thing in 256 colours is about
    a third of that, which matters when the whole APK is under a megabyte.
    """
    try:
        reduced = image.quantize(colors=colors, method=Image.FASTOCTREE,
                                 dither=Image.FLOYDSTEINBERG)
        reduced.save(path, optimize=True)
    except Exception:
        # Anything Pillow will not quantize just gets saved as it is.
        image.save(path, optimize=True)


def make_launcher_icons(seal: Image.Image) -> None:
    """Legacy square icons, for Android 7 which has no adaptive icons."""
    for density, scale in LAUNCHER_DENSITIES.items():
        size = int(LAUNCHER_DP * scale)

        # The seal has a transparent, scalloped edge. On a plain icon a
        # transparent edge looks like a hole on a dark wallpaper, so it sits
        # on a white tile with a little breathing room.
        tile = Image.new("RGBA", (size, size), (255, 255, 255, 255))
        inner = int(size * 0.88)
        tile.alpha_composite(seal.resize((inner, inner), Image.LANCZOS),
                             ((size - inner) // 2, (size - inner) // 2))

        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        save_png(tile, folder / "ic_launcher.png")
        print(f"  mipmap-{density}/ic_launcher.png  {size}x{size}  "
              f"({(folder / 'ic_launcher.png').stat().st_size / 1024:.1f} KB)")


def make_adaptive_icon(seal: Image.Image) -> None:
    """The Android 8+ icon: a background colour plus a foreground layer."""
    canvas = Image.new("RGBA", (ADAPTIVE_CANVAS_PX, ADAPTIVE_CANVAS_PX), (0, 0, 0, 0))
    inner = int(ADAPTIVE_CANVAS_PX * ADAPTIVE_SAFE_DP / ADAPTIVE_CANVAS_DP)
    canvas.alpha_composite(
        seal.resize((inner, inner), Image.LANCZOS),
        ((ADAPTIVE_CANVAS_PX - inner) // 2, (ADAPTIVE_CANVAS_PX - inner) // 2))

    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    foreground = nodpi / "ic_launcher_foreground.png"
    save_png(canvas, foreground)
    print(f"  drawable-nodpi/ic_launcher_foreground.png  {ADAPTIVE_CANVAS_PX}x{ADAPTIVE_CANVAS_PX}  "
          f"({foreground.stat().st_size / 1024:.0f} KB)")

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Android 8 and up. The launcher masks this to whatever shape the\n'
        '     phone uses, which is why the seal is kept inside the middle 66dp. -->\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n')

    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        folder = RES / "mipmap-anydpi-v26"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / name).write_text(adaptive, encoding="utf-8")
        print(f"  mipmap-anydpi-v26/{name}")


def make_header_logo(seal: Image.Image) -> None:
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    out = nodpi / "school_logo.png"
    save_png(seal.resize((HEADER_LOGO_PX, HEADER_LOGO_PX), Image.LANCZOS), out)
    print(f"  drawable-nodpi/school_logo.png  {HEADER_LOGO_PX}x{HEADER_LOGO_PX}  "
          f"({out.stat().st_size / 1024:.0f} KB)")


def make_banner(banner: Image.Image) -> None:
    """The home screen background.

    Kept as a JPEG because the source is a flat photo-like graphic and PNG
    would cost about ten times the space for no visible gain.
    """
    height = int(banner.height * BANNER_WIDTH_PX / banner.width)
    out = RES / "drawable-nodpi" / "school_banner.jpg"
    banner.convert("RGB").resize((BANNER_WIDTH_PX, height), Image.LANCZOS).save(
        out, "JPEG", quality=82, optimize=True, progressive=True)
    print(f"  drawable-nodpi/school_banner.jpg  {BANNER_WIDTH_PX}x{height}  "
          f"({out.stat().st_size / 1024:.0f} KB)")


def report_palette(seal: Image.Image, banner: Image.Image) -> None:
    """Prints the colours worth putting in colors.xml, sampled from the art."""
    def hex_of(image: Image.Image, x: int, y: int) -> str:
        r, g, b = image.convert("RGB").getpixel((x, y))
        return f"#{r:02X}{g:02X}{b:02X}"

    print("\ncolours sampled from the artwork:")
    print(f"  seal outer ring   {hex_of(seal, seal.width // 2, 8)}")
    print(f"  seal ring left    {hex_of(seal, 18, seal.height // 2)}")
    print(f"  seal gold centre  {hex_of(seal, seal.width // 2, seal.height // 2)}")
    print(f"  banner green      {hex_of(banner, banner.width - 40, banner.height // 2)}")
    print(f"  banner cream      {hex_of(banner, 40, 40)}")


def main() -> None:
    print(f"seal   : {SEAL}")
    print(f"banner : {BANNER}\n")

    seal = load(SEAL)
    banner = load(BANNER)

    make_launcher_icons(seal)
    make_adaptive_icon(seal)
    make_header_logo(seal)
    make_banner(banner)
    report_palette(seal, banner)

    print("\ndone.")


if __name__ == "__main__":
    main()
