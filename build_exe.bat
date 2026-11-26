@echo off
REM Double-click this file on Windows to build Easy Video Downloader.
REM Makes a program folder (dist\Easy Video Downloader\) and, if Inno Setup 6 is installed,
REM a single installer (dist\Easy-Video-Downloader-Setup.exe).
REM Needs Python 3.9+ from python.org (tick "Add python.exe to PATH" when installing).
setlocal
cd /d "%~dp0"

where py >nul 2>nul && (set "PY=py -3") || (set "PY=python")
%PY% --version >nul 2>nul || (
    echo Python was not found. Install it from https://www.python.org/downloads/ and run this again.
    pause & exit /b 1
)

echo [1/3] Preparing a private build environment...
if not exist ".build-venv\Scripts\python.exe" %PY% -m venv .build-venv || goto :fail
".build-venv\Scripts\python.exe" -m pip install --quiet --upgrade pip pyinstaller || goto :fail

echo [2/3] Building the program (takes about a minute)...
".build-venv\Scripts\python.exe" -m PyInstaller --noconfirm --clean --onedir --windowed ^
    --name "Easy Video Downloader" --icon icon.ico --add-data "icon.ico;." ^
    --version-file version_info.txt --noupx ^
    easy_video_downloader.py || goto :fail

echo [3/3] Making the installer...
set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if exist "%ISCC%" (
    "%ISCC%" /Qp installer.iss || goto :fail
    echo.
    echo Your installer is here:  %~dp0dist\Easy-Video-Downloader-Setup.exe
    echo Give that one file to anyone. Python is NOT needed on their PC.
) else (
    echo Inno Setup 6 is not installed, so no installer was made.
    echo The program folder is here:  %~dp0dist\Easy Video Downloader\
    echo Keep the whole folder together; start "Easy Video Downloader.exe" inside it.
)
explorer "%~dp0dist"
pause
exit /b 0

:fail
echo.
echo The build did not finish. Please read the message above.
pause
exit /b 1
