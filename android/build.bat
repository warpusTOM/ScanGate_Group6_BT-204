@echo off
REM Double-click this to build ScanGate.apk.
REM Output goes to dist\ScanGate.apk
cd /d "%~dp0"

echo.
echo   ScanGate Android build
echo   ----------------------
echo.

python tools\build_apk.py --test
if errorlevel 1 (
    echo.
    echo   Build failed. Read the messages above.
) else (
    echo.
    echo   Done. The APK is in dist\ScanGate.apk
)

echo.
pause
