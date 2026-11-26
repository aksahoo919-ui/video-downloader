# Installs Easy Video Downloader from the Setup.exe, opens and closes it several times
# (normally and by force), and checks that nothing new is left in %TEMP%.
# Run on Windows:  pwsh tests/windows_temp_check.ps1 -Setup dist\Easy-Video-Downloader-Setup.exe
param([Parameter(Mandatory = $true)][string]$Setup)
$ErrorActionPreference = 'Stop'

$appDir   = Join-Path $env:LOCALAPPDATA 'Programs\Easy Video Downloader'
$exe      = Join-Path $appDir 'Easy Video Downloader.exe'
$dataDir  = Join-Path $env:LOCALAPPDATA 'EasyVideoDownloader'
$runtime  = Join-Path $dataDir 'runtime'
$failures = @()

function Get-TempMei { @(Get-ChildItem -LiteralPath $env:TEMP -Directory -Filter '_MEI*' -ErrorAction SilentlyContinue | ForEach-Object FullName) }
function Get-RuntimeMei { @(Get-ChildItem -LiteralPath $runtime -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '_MEI*' -or $_.Name -like 'stale-*' } | ForEach-Object Name) }

function Start-App {
    $p = Start-Process -FilePath $exe -PassThru
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        $p.Refresh()
        if ($p.HasExited) { throw "The app exited by itself (code $($p.ExitCode))." }
        if ($p.MainWindowHandle -ne 0) { return $p }
        Start-Sleep -Milliseconds 500
    }
    throw 'The app window did not appear within 60 seconds.'
}

function Close-AppNormally($p) {
    # Same as clicking the window's X button.
    [void]$p.CloseMainWindow()
    if (-not $p.WaitForExit(30000)) { throw 'The app did not close within 30 seconds of closing its window.' }
}

# Something unrelated in %TEMP% that looks like a leftover: it must NOT be touched.
$decoy = Join-Path $env:TEMP '_MEI999999'
New-Item -ItemType Directory -Force -Path $decoy | Out-Null
Set-Content -Path (Join-Path $decoy 'not-ours.txt') -Value 'decoy'
(Get-Item $decoy).LastWriteTime = (Get-Date).AddDays(-2)

$before = Get-TempMei
Write-Host "_MEI folders in %TEMP% before: $($before.Count)"

# ---------------------------------------------------------------- install
Write-Host "Installing $Setup ..."
$inst = Start-Process -FilePath $Setup -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CURRENTUSER' -Wait -PassThru
if ($inst.ExitCode -ne 0) { throw "Setup failed with code $($inst.ExitCode)." }
if (-not (Test-Path $exe)) { throw "Program not found at $exe" }
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Easy Video Downloader.lnk'
if (-not (Test-Path $startMenu)) { $failures += 'Start menu shortcut missing' }
Write-Host "Installed to: $exe"
Get-ChildItem $appDir | Format-Table Name, Length -AutoSize | Out-String | Write-Host

# A stale leftover from an "earlier run" in the app's own runtime folder: must be removed on start.
New-Item -ItemType Directory -Force -Path (Join-Path $runtime '_MEI424242') | Out-Null
Set-Content -Path (Join-Path $runtime '_MEI424242\libcrypto-3.dll') -Value 'old'
(Get-Item (Join-Path $runtime '_MEI424242')).LastWriteTime = (Get-Date).AddHours(-3)

# ---------------------------------------------------------------- run 1: first start downloads the tools
Write-Host 'Run 1: first start (downloads yt-dlp, FFmpeg, Deno), then close normally'
$p = Start-App
$deadline = (Get-Date).AddSeconds(420)
while (-not ((Test-Path "$dataDir\bin\ffmpeg.exe") -and (Test-Path "$dataDir\bin\yt-dlp.exe")) -and (Get-Date) -lt $deadline) { Start-Sleep 3 }
if (-not (Test-Path "$dataDir\bin\ffmpeg.exe")) { $failures += 'First-time setup did not finish in time' }
Start-Sleep 20   # let setup finish (Deno, yt-dlp --version)
Close-AppNormally $p
if (Get-RuntimeMei | Where-Object { $_ -eq '_MEI424242' }) { $failures += 'Stale runtime leftover was not removed' } else { Write-Host '  stale runtime leftover was removed' }

# ---------------------------------------------------------------- runs 2-4: normal close
foreach ($i in 2..4) {
    Write-Host "Run ${i}: open, wait, close normally"
    $p = Start-App
    Start-Sleep 12
    Close-AppNormally $p
}

# ---------------------------------------------------------------- runs 5-6: rough close
foreach ($i in 5..6) {
    Write-Host "Run ${i}: open, then force-kill (like End task in Task Manager)"
    $p = Start-App
    Start-Sleep 3
    Stop-Process -Id $p.Id -Force
    $p.WaitForExit(15000) | Out-Null
}
Start-Sleep 15   # any yt-dlp check started by a killed window finishes on its own

# ---------------------------------------------------------------- yt-dlp killed mid-run (what Stop does)
Write-Host 'Run 7: force-kill the download engine while it is running, as the Stop button does'
$psi = [Diagnostics.ProcessStartInfo]::new("$dataDir\bin\yt-dlp.exe", '--version')
$psi.UseShellExecute = $false
$psi.Environment['TMP'] = $runtime
$psi.Environment['TEMP'] = $runtime
$y = [Diagnostics.Process]::Start($psi)
Start-Sleep -Milliseconds 800
& taskkill /F /T /PID $y.Id | Out-Null
Start-Sleep 2
Write-Host "  runtime folder after the kill: $((Get-RuntimeMei) -join ', ')"
Write-Host '  waiting 65 s, then opening the app so it cleans up...'
Start-Sleep 65
$p = Start-App
Start-Sleep 12
Close-AppNormally $p
$left = Get-RuntimeMei
Write-Host "  runtime folder after the next start: $($left -join ', ')"
if ($left.Count -gt 0) { $failures += "Runtime leftovers not cleaned: $($left -join ', ')" }

# ---------------------------------------------------------------- results
Get-Process -Name 'yt-dlp', 'ffmpeg', 'deno', 'Easy Video Downloader' -ErrorAction SilentlyContinue | Stop-Process -Force
$after = Get-TempMei
$new = @($after | Where-Object { $before -notcontains $_ })
Write-Host "_MEI folders in %TEMP% after:  $($after.Count)"
if ($new.Count -gt 0) { $failures += "New _MEI folders in %TEMP%: $($new -join ', ')" } else { Write-Host 'No new _MEI folders in %TEMP%.' }
if (-not (Test-Path (Join-Path $decoy 'not-ours.txt'))) { $failures += 'An unrelated _MEI folder in %TEMP% was touched' } else { Write-Host 'Unrelated folder in %TEMP% left alone.' }
Remove-Item -Recurse -Force $decoy

# ---------------------------------------------------------------- uninstall
$unins = Join-Path $appDir 'unins000.exe'
Start-Process -FilePath $unins -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait
Start-Sleep 3
if (Test-Path $exe) { $failures += 'Uninstall left the program behind' } else { Write-Host 'Uninstall removed the program.' }

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL: $_" }
    exit 1
}
Write-Host 'ALL CHECKS PASSED'
