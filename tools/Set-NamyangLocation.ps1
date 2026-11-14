param(
    [string]$Serial = 'emulator-5554',
    [string]$AdbPath = ''
)
$ErrorActionPreference = 'Stop'
if (-not $AdbPath) {
    $properties = Join-Path $PSScriptRoot '..\local.properties'
    $sdkLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'Pass -AdbPath with the full path to platform-tools\adb.exe.' }
    $sdkPath = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
    $AdbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
}
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only an Android emulator is supported.' }
$isEmulator = & $AdbPath -s $Serial shell getprop ro.kernel.qemu
if ($LASTEXITCODE -ne 0 -or "$isEmulator".Trim() -ne '1') { throw 'The selected emulator is not connected.' }
# Android console order: longitude, latitude. Reference point, not a surveyed entrance.
& $AdbPath -s $Serial emu geo fix 126.823083 37.1552771
if ($LASTEXITCODE -ne 0) { throw 'Failed to send emulator GPS fix.' }
Write-Host 'Namyang reference location sent. Confirm the location in Navigation, then press the scenario button in SteadyRide.'
