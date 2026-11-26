; Inno Setup script for Easy Video Downloader.
; Turns the PyInstaller folder build (dist\Easy Video Downloader\) into one Setup.exe.
; Installs for the current user only (no admin rights needed) into
;   %LOCALAPPDATA%\Programs\Easy Video Downloader\Easy Video Downloader.exe
; Build:  ISCC.exe /DAppVersion=1.0.123 installer.iss

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppName "Easy Video Downloader"
#define AppExe "Easy Video Downloader.exe"

[Setup]
AppId={{E63A921E-57F6-4D3B-800D-CA5270CFECFA}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName}
AppPublisher={#AppName}
VersionInfoVersion={#AppVersion}
VersionInfoDescription={#AppName} Setup
DefaultDirName={localappdata}\Programs\{#AppName}
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
OutputDir=dist
OutputBaseFilename=Easy-Video-Downloader-Setup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; Close a running copy before updating it.
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[InstallDelete]
; Remove files from the previous version so old libraries never linger.
Type: filesandordirs; Name: "{app}\_internal"

[Files]
Source: "dist\{#AppName}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Open {#AppName} now"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; The private temp folder used by the download engine. Settings and the engine itself
; (in %LOCALAPPDATA%\EasyVideoDownloader) are kept so a reinstall starts quickly.
Type: filesandordirs; Name: "{localappdata}\EasyVideoDownloader\runtime"
