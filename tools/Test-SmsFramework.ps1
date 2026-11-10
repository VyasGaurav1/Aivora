param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME.' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$avd = (& $adb -s $Serial emu avd name) -join ' '
if ($avd -notmatch 'Aivora_Test_API(30|35)\b') { throw 'Only the isolated AIVORA emulator images are allowed.' }
$api = $Matches[1]
$gsm = (& $adb -s $Serial emu gsm status) -join "`n"
if ($gsm -notmatch 'gsm voice state:\s+home' -or $gsm -notmatch 'gsm data state:\s+home') { throw 'Start with emulator voice/data registration at home.' }
if ((& $adb -s $Serial shell cmd connectivity airplane-mode).Trim() -ne 'disabled') { throw 'Start with airplane mode disabled.' }
$package = (& $adb -s $Serial shell dumpsys package com.chronoit.aivora) -join "`n"
$smsGranted = $package -match 'android.permission.SEND_SMS: granted=true'
$phoneGranted = $package -match 'android.permission.READ_PHONE_STATE: granted=true'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest --offline
    if ($LASTEXITCODE -ne 0) { throw 'Probe build failed.' }
    & $adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Application install failed.' }
    & $adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test install failed.' }
    & $adb -s $Serial shell pm grant com.chronoit.aivora android.permission.SEND_SMS
    & $adb -s $Serial shell pm grant com.chronoit.aivora android.permission.READ_PHONE_STATE
    & $adb -s $Serial emu gsm voice unregistered
    & $adb -s $Serial emu gsm data unregistered
    $result = & $adb -s $Serial shell am instrument -w -e class com.chronoit.aivora.SmsFrameworkProbe -e smsFrameworkProbe true com.chronoit.aivora.test/androidx.test.runner.AndroidJUnitRunner
    $result | Set-Content -LiteralPath "app/build/reports/sms-framework-api$api-probe.txt" -Encoding utf8
    $result | Write-Output
    if (($result -join "`n") -notmatch 'OK \(1 test\)' -or ($result -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw 'SMS framework probe did not pass.' }
} finally {
    & $adb -s $Serial emu gsm voice home
    & $adb -s $Serial emu gsm data home
    if (-not $smsGranted) { & $adb -s $Serial shell pm revoke com.chronoit.aivora android.permission.SEND_SMS }
    if (-not $phoneGranted) { & $adb -s $Serial shell pm revoke com.chronoit.aivora android.permission.READ_PHONE_STATE }
    Pop-Location
}
