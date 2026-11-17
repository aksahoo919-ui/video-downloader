# Easy Video Downloader

A simple Windows program for downloading videos or audio (MP3 etc.) from a link.
Paste the link, pick Video or Audio, press **Download**.

## Download

**[Download Easy Video Downloader for Windows](https://github.com/aksahoo919-ui/video-downloader/releases/latest/download/Easy-Video-Downloader.exe)**

Click the link, then double-click the downloaded file. Nothing to install, no Python needed.

New to the program? Read the **[User Guide](USER_GUIDE.md)** for step-by-step help.

## Files

| File | What it is |
| --- | --- |
| `easy_video_downloader.py` | The program (Python + Tkinter, no extra packages needed) |
| `build_exe.bat` | Double-click on Windows to produce the `.exe` |
| `icon.ico`, `icon.png` | Program icon |

## Making the .exe

Every push to `main` builds the `.exe` automatically on GitHub and publishes it as a new release,
so the download link above always gives the newest version.

To build it yourself instead:

1. On a Windows PC, install Python from python.org (tick *Add python.exe to PATH*).
2. Double-click `build_exe.bat`.
3. Take `dist\Easy Video Downloader.exe` and give it to anyone. They do **not** need Python.

## How automatic updates work

The download engine (yt-dlp) is not baked into the `.exe`. It lives in
`%LOCALAPPDATA%\EasyVideoDownloader\bin`:

- **First start:** downloads the newest yt-dlp, FFmpeg and Deno (one time, roughly 260 MB, needs internet).
- **Normal starts:** no update check, so the program opens straight away and works offline.
- **When a download fails:** if the error is one a newer engine could fix (not, for example, a private
  video, a full disk or no internet), the program checks for a newer yt-dlp. If there is one, it downloads
  it, checks it against the published checksum, swaps it in, and retries that download once. To avoid
  repeated checks, it checks at most once every 30 minutes.
- **Manually:** the *Check for updates* link at the bottom of the window.

## Notes for whoever hands this out

- The first time the `.exe` runs, Windows SmartScreen may say "Windows protected your PC" because the
  file is not code-signed. Click **More info → Run anyway**.
- Downloads go to `Downloads\Video Downloads` unless the user picks another folder (it is remembered).
- To reset everything, delete the folder `%LOCALAPPDATA%\EasyVideoDownloader`.
- Only download content you have the right to download.
