"""Test layer 1 - source contract checks.

These are the mistakes that compile fine and then crash on the phone.

The big one: findViewById(R.id.something) where "something" is declared in a
different layout than the one the screen actually shows. The compiler is happy,
because R.id.something exists. At runtime the view is not there, findViewById
returns null, and the app dies with a NullPointerException the moment the
screen opens. This file catches that before the apk is even built.

It also checks that every @string, @color and @drawable named in a layout
really exists, and that every @id reference points at something declared with
@+id.

    python tools/check_source.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app" / "src" / "main"
JAVA_DIR = MAIN / "java"
RES_DIR = MAIN / "res"
MANIFEST = MAIN / "AndroidManifest.xml"

PACKAGE_DIR = JAVA_DIR / "com" / "scangate" / "app"

DECLARED_ID = re.compile(r'@\+id/([A-Za-z0-9_]+)')
REFERENCED_ID = re.compile(r'@id/([A-Za-z0-9_]+)')
LAYOUT_RESOURCE_REF = re.compile(r'@(string|color|drawable|mipmap|layout)/([A-Za-z0-9_]+)')

JAVA_RESOURCE_REF = re.compile(r'R\.(id|string|color|drawable|mipmap|layout)\.([A-Za-z0-9_]+)')

# Screens and helpers that work on views belonging to a layout they never name
# themselves. ResultCard reaches into activity_main, and ScanLogAdapter builds
# rows from row_scan_log, but neither calls setContentView.
EXTRA_LAYOUT_SCOPE = {
    "ui/ResultCard.java": ["activity_main"],
    "ui/ScanLogAdapter.java": ["row_scan_log"],
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def declared_in_layouts() -> tuple[dict, dict]:
    """Returns (id -> set of layouts declaring it, layout -> set of ids)."""
    id_to_layouts: dict[str, set] = {}
    layout_ids: dict[str, set] = {}

    for layout in sorted((RES_DIR / "layout").glob("*.xml")):
        text = read(layout)
        ids = set(DECLARED_ID.findall(text))
        layout_ids[layout.stem] = ids
        for name in ids:
            id_to_layouts.setdefault(name, set()).add(layout.stem)

    return id_to_layouts, layout_ids


def declared_values() -> dict:
    """Everything that can be named from a layout or from Java."""
    strings: set = set()
    colors: set = set()
    for values_file in sorted((RES_DIR / "values").glob("*.xml")):
        text = read(values_file)
        strings |= set(re.findall(r'<string\s+name="([A-Za-z0-9_]+)"', text))
        colors |= set(re.findall(r'<color\s+name="([A-Za-z0-9_]+)"', text))

    drawables = {path.stem for path in (RES_DIR / "drawable").glob("*") if path.is_file()}
    drawables |= {path.stem for path in (RES_DIR / "drawable-nodpi").glob("*") if path.is_file()}
    layouts = {path.stem for path in (RES_DIR / "layout").glob("*.xml")}

    # Launcher icons live in mipmap-* folders, one file per screen density,
    # plus the adaptive icon XML in mipmap-anydpi-v26.
    mipmaps: set = set()
    for folder in RES_DIR.glob("mipmap*"):
        for path in folder.iterdir():
            if path.is_file():
                mipmaps.add(path.stem)

    return {
        "string": strings,
        "color": colors,
        "drawable": drawables,
        "layout": layouts,
        "mipmap": mipmaps,
    }


def check_layouts(values: dict) -> list:
    problems = []
    id_to_layouts, layout_ids = declared_in_layouts()

    for layout in sorted((RES_DIR / "layout").glob("*.xml")):
        text = read(layout)
        own_ids = layout_ids[layout.stem]

        for name in sorted(set(REFERENCED_ID.findall(text))):
            if name not in own_ids:
                problems.append(
                    f"{layout.name}: uses @id/{name} but nothing in this layout "
                    f"declares @+id/{name}")

        for kind, name in LAYOUT_RESOURCE_REF.findall(text):
            if kind == "id":
                continue
            if name not in values[kind]:
                problems.append(f"{layout.name}: @{kind}/{name} is not defined anywhere")

    return problems


def check_java(values: dict, id_to_layouts: dict) -> list:
    problems = []

    for source in sorted(PACKAGE_DIR.rglob("*.java")):
        text = read(source)
        relative = source.relative_to(PACKAGE_DIR).as_posix()

        # Layouts this file is allowed to find views in.
        own_layouts = set(re.findall(r'R\.layout\.([A-Za-z0-9_]+)', text))
        own_layouts |= set(EXTRA_LAYOUT_SCOPE.get(relative, []))

        visible_ids: set = set()
        for layout in own_layouts:
            visible_ids |= set(DECLARED_ID.findall(read(RES_DIR / "layout" / f"{layout}.xml")))

        for kind, name in JAVA_RESOURCE_REF.findall(text):
            if kind == "id":
                # View ids are declared in layouts, not in values/*.xml, so
                # this is the check that catches a lookup aimed at a screen
                # the class never shows.
                if name in visible_ids:
                    continue
                where = ", ".join(sorted(id_to_layouts.get(name, {"nowhere"})))
                scope = ", ".join(sorted(own_layouts)) or "no layout"
                problems.append(
                    f"{relative}: looks up R.id.{name} but that view lives in "
                    f"{where}, while this file only works with {scope}")
                continue

            if name not in values[kind]:
                problems.append(f"{relative}: R.{kind}.{name} is not defined anywhere")

    return problems


def check_manifest(values: dict) -> list:
    problems = []
    if not MANIFEST.exists():
        return ["AndroidManifest.xml is missing"]

    text = read(MANIFEST)
    for name in re.findall(r'<activity\s+android:name="\.([A-Za-z0-9_]+)"', text):
        if not (PACKAGE_DIR / f"{name}.java").exists():
            problems.append(f"AndroidManifest.xml lists activity .{name} "
                            f"but {name}.java does not exist")

    if 'android.permission.CAMERA' not in text:
        problems.append("AndroidManifest.xml does not ask for the CAMERA permission")
    if 'android.intent.action.MAIN' not in text:
        problems.append("AndroidManifest.xml has no launcher activity")

    # The launcher icon is a mipmap, not a drawable, because it comes in one
    # file per screen density.
    for name in re.findall(r'android:(?:icon|roundIcon)="@mipmap/([A-Za-z0-9_]+)"', text):
        if name not in values["mipmap"]:
            problems.append(f"AndroidManifest.xml points at @mipmap/{name} "
                            f"but no mipmap folder has that file")

    if 'android:icon=' not in text:
        problems.append("AndroidManifest.xml has no launcher icon")

    return problems


def report_unused(values: dict) -> None:
    """Informational only: strings nobody reads. Not an error."""
    used: set = set()
    for path in list((RES_DIR / "layout").glob("*.xml")) + [MANIFEST]:
        used |= set(re.findall(r'@string/([A-Za-z0-9_]+)', read(path)))
    for source in PACKAGE_DIR.rglob("*.java"):
        used |= set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', read(source)))

    unused = sorted(values["string"] - used)
    if unused:
        print(f"note: {len(unused)} string(s) declared but not used: "
              + ", ".join(unused))


def check_sources() -> list:
    values = declared_values()
    id_to_layouts, _ = declared_in_layouts()

    problems = []
    problems += check_layouts(values)
    problems += check_java(values, id_to_layouts)
    problems += check_manifest(values)

    if problems:
        print("\nsource checks FAILED:")
        for problem in problems:
            print("  -", problem)
        raise SystemExit(1)

    java_files = len(list(PACKAGE_DIR.rglob("*.java")))
    layouts = len(list((RES_DIR / "layout").glob("*.xml")))
    print(f"source checks: OK ({java_files} java files, {layouts} layouts, "
          f"{len(values['string'])} strings)")
    report_unused(values)
    return problems


if __name__ == "__main__":
    check_sources()
