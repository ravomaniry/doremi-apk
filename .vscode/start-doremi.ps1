$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$line = Get-Content local.properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if (-not $line) {
    Write-Error 'sdk.dir is missing from local.properties.'
    exit 1
}

$sdk = ($line -replace '^sdk\.dir=', '').Trim().TrimEnd("`r")
$sdk = $sdk -replace '\\:', ':' -replace '\\\\', '\'

if ($env:ANDROID_HOME) {
    $root = $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $root = $env:ANDROID_SDK_ROOT
} else {
    $root = $sdk
}

$adb = Join-Path $root 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $cmd) {
        Write-Error 'adb not found. Set sdk.dir in local.properties or add platform-tools to PATH.'
        exit 1
    }
    $adb = $cmd.Source
}

& $adb shell am start -n mg.maniry.doremii/.browser.BrowserActivity
exit $LASTEXITCODE
