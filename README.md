# Easy Video Downloader

A simple Windows program and Android app for downloading videos or audio (MP3 etc.) from a link.
Paste the link, pick Video or Audio, press **Download**.

## Download

**[Download Easy Video Downloader for Windows](https://github.com/aksahoo919-ui/video-downloader/releases/latest/download/Easy-Video-Downloader-Setup.exe)**

Click the link, then double-click the downloaded installer. It installs for the current user (no admin
rights, no Python needed) into `%LOCALAPPDATA%\Programs\Easy Video Downloader` and adds Start menu and
desktop shortcuts. The older link ending in `Easy-Video-Downloader.exe` now gives the same installer.

**[Download Easy Video Downloader for Android](https://github.com/aksahoo919-ui/video-downloader/releases/latest/download/Easy-Video-Downloader.apk)**

Open the link on your phone, open the downloaded file and tap **Install** (Android 7 or newer).

New to the program? Read the **[User Guide](USER_GUIDE.md)** for step-by-step help.

## Files

| File | What it is |
| --- | --- |
| `easy_video_downloader.py` | The program (Python + Tkinter, no extra packages needed) |
| `build_exe.bat` | Double-click on Windows to build the program folder and installer |
| `installer.iss` | Inno Setup script that turns the program folder into one Setup.exe |
| `tests/windows_temp_check.ps1` | Windows test: installs, opens and closes the app, checks nothing is left in `%TEMP%` |
| `icon.ico`, `icon.png` | Program icon |
| `android/` | The Android app (Kotlin). Built into `Easy-Video-Downloader.apk` by GitHub Actions |

## Building the Windows program

Every push to `main` builds the program on GitHub, tests it on a clean Windows machine and publishes the
installer as a new release, so the download link above always gives the newest version.

The program is a PyInstaller **folder build** (`--onedir`): it runs straight from its install folder.
The older single-file build (`--onefile`) unpacked itself into a `_MEI...` folder in `%TEMP%` on every
start, and that folder was left behind whenever the program was killed or crashed.

To build it yourself instead:

1. On a Windows PC, install Python from python.org (tick *Add python.exe to PATH*), and optionally
   [Inno Setup 6](https://jrsoftware.org/isdl.php) for the installer.
2. Double-click `build_exe.bat`.
3. Give `dist\Easy-Video-Downloader-Setup.exe` to anyone. They do **not** need Python. (Without Inno Setup
   you get the folder `dist\Easy Video Downloader\`; keep it together and start the `.exe` inside.)

## Temporary files

The download engine, `yt-dlp.exe`, is itself a single-file program that unpacks into a `_MEI...` folder
each time it runs. The app gives it a private temp folder, `%LOCALAPPDATA%\EasyVideoDownloader\runtime`,
instead of `%TEMP%`. On each start, it removes leftovers there from earlier runs (for example after
**Stop**, which ends yt-dlp at once). Folders still in use are skipped, and `%TEMP%` itself is never
touched. Closing the window stops every process the app started.

## How automatic updates work

The download engine (yt-dlp) is not baked into the program. It lives in
`%LOCALAPPDATA%\EasyVideoDownloader\bin`:

- **First start:** downloads the newest yt-dlp, FFmpeg and Deno (one time, roughly 260 MB, needs internet).
- **Normal starts:** no update check, so the program opens straight away and works offline.
- **When a download fails:** if the error is one a newer engine could fix (not, for example, a private
  video, a full disk or no internet), the program checks for a newer yt-dlp. If there is one, it downloads
  it, checks it against the published checksum, swaps it in, and retries that download once. To avoid
  repeated checks, it checks at most once every 30 minutes.
- **Manually:** the *Check for updates* link at the bottom of the window.

## Notes for whoever hands this out

- The first time the installer runs, Windows SmartScreen may say "Windows protected your PC" because the
  file is not code-signed. Click **More info → Run anyway**.
- Downloads go to `Downloads\Video Downloads` unless the user picks another folder (it is remembered).
- To reset everything, delete the folder `%LOCALAPPDATA%\EasyVideoDownloader`.
- Only download content you have the right to download.

## The Android app

The Android app lives in `android/` and works like the Windows program: paste a link (or share it from
YouTube or the browser), pick Video or Audio and a quality, and tap **Download**. Files go to
`Downloads/Video Downloads`. Downloads keep running in the background with a progress notification.

- **Engine:** uses [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), which bundles
  yt-dlp, Python and FFmpeg inside the APK, so nothing is downloaded on first start.
- **Updates:** same as Windows. The engine is updated only when a download fails with an error a newer
  engine could fix (at most every 30 minutes), or from *Check for updates*.
- **Building:** every push to `main` builds the APK on GitHub (`./gradlew assembleRelease` in `android/`)
  and adds it to the same release as the `.exe`.
- **Signing:** Android only installs an update over an existing app when both are signed with the same key.
  The key in `android/keystore/` is used by default so every build matches. It is public, so anyone could
  sign an APK with it; for a private key, add the GitHub secrets `ANDROID_KEYSTORE_FILE`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` and pass them to the build step.
