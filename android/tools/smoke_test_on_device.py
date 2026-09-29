"""Install the APK on a real phone and check it actually starts.

This is the one test that cannot run on a laptop. Everything else in tools/
can, which is why the emulator is not needed for normal development. But a
startup crash is the one failure static checks never catch, so this script
exists for when a phone is plugged in.

It does five things:

    1. checks a device is connected and authorised
    2. installs the APK, replacing any older copy
    3. launches the home screen and waits for it to report itself drawn
    4. launches the camera screen directly, to prove the camera path starts
       without crashing
    5. reads back the crash buffer and the app's own log lines, and saves a
       screenshot next to the APK

    python tools/smoke_test_on_device.py

Nothing here modifies your phone beyond installing the app. Uninstall with:
    adb uninstall com.scangate.app
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "dist" / "ScanGate.apk"
SCREENSHOT = ROOT / "dist" / "smoke-test-screen.png"

ADB = Path(r"C:\Users\Administrator\android-sdk\platform-tools\adb.exe")
PACKAGE = "com.scangate.app"
HOME_SCREEN = PACKAGE + "/.MainActivity"
CAMERA_SCREEN = PACKAGE + "/.CameraScanActivity"


def adb(*arguments: str, check: bool = True) -> str:
    result = subprocess.run([str(ADB), *arguments],
                            capture_output=True, text=True, check=False)
    if check and result.returncode != 0:
        raise SystemExit(f"adb {' '.join(arguments)} failed:\n{result.stderr.strip()}")
    return result.stdout.strip()


def require_device() -> None:
    if not ADB.exists():
        raise SystemExit(f"adb not found at {ADB}")

    output = adb("devices")
    lines = [line for line in output.splitlines()[1:] if line.strip()]
    ready = [line for line in lines if line.endswith("\tdevice")]

    if not ready:
        print(output)
        raise SystemExit("No phone connected. Plug it in, turn on USB debugging, "
                         "and accept the authorisation prompt on the phone.")

    print(f"device: {ready[0].split()[0]}")


def install() -> None:
    if not APK.exists():
        raise SystemExit(f"{APK} does not exist yet. Run tools/build_apk.py first.")

    print(f"installing {APK.name} ({APK.stat().st_size / 1e6:.1f} MB)")
    output = adb("install", "-r", "-d", str(APK), check=False)
    print(" ", output.splitlines()[-1] if output else "no output")
    if "Success" not in output:
        raise SystemExit("install did not report success")


def launch(activity: str, label: str) -> bool:
    adb("shell", "am", "force-stop", PACKAGE, check=False)
    time.sleep(0.6)

    output = adb("shell", "am", "start", "-W", "-n", activity, check=False)
    for line in output.splitlines():
        if line.startswith(("Status:", "Error:", "Activity:")):
            print("  " + line.strip())

    if "Error" in output or "Exception" in output:
        return False

    time.sleep(2.5)
    running = adb("shell", "pidof", PACKAGE, check=False)
    if running:
        print(f"  {label} is running, pid {running.split()[0]}")
        return True

    print(f"  {label} is NOT running, it crashed")
    return False


def read_crashes() -> bool:
    """Returns True when the crash buffer is empty."""
    crashes = adb("logcat", "-d", "-b", "crash", "-t", "200", check=False)
    ours = [line for line in crashes.splitlines()
            if PACKAGE in line or "AndroidRuntime" in line]

    if not ours:
        print("  no crashes recorded")
        return True

    print("  CRASH BUFFER:")
    for line in ours[-25:]:
        print("   ", line)
    return False


def read_app_log() -> None:
    lines = adb("logcat", "-d", "-t", "400", check=False)
    ours = [line for line in lines.splitlines() if PACKAGE in line]
    if ours:
        print("  app log lines:")
        for line in ours[-10:]:
            print("   ", line)


def current_focus() -> None:
    window = adb("shell", "dumpsys", "window", check=False)
    for line in window.splitlines():
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            print("  " + line.strip())


def take_screenshot() -> None:
    SCREENSHOT.parent.mkdir(parents=True, exist_ok=True)
    with open(SCREENSHOT, "wb") as handle:
        subprocess.run([str(ADB), "exec-out", "screencap", "-p"],
                       stdout=handle, check=False)
    if SCREENSHOT.exists() and SCREENSHOT.stat().st_size > 1000:
        print(f"  screenshot -> {SCREENSHOT}")
    else:
        print("  screenshot failed (harmless)")


def main() -> None:
    require_device()
    install()

    adb("shell", "pm", "grant", PACKAGE, "android.permission.CAMERA", check=False)

    print("\nhome screen")
    home_ok = launch(HOME_SCREEN, "home screen")
    current_focus()
    take_screenshot()

    print("\ncamera screen")
    camera_ok = launch(CAMERA_SCREEN, "camera screen")
    current_focus()
    time.sleep(1.5)
    take_screenshot()

    print("\ncrash check")
    crash_ok = read_crashes()
    read_app_log()

    print()
    if home_ok and camera_ok and crash_ok:
        print("smoke test: PASSED")
        print("Both screens opened and stayed open. Look at the screenshot, then "
              "point the camera at a student ID to confirm a real scan.")
    else:
        print("smoke test: FAILED")
        sys.exit(1)


if __name__ == "__main__":
    main()
