param([string]$Serial)
$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to your Android SDK directory.' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not $Serial) {
    $devices = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Specify -Serial when there is not exactly one connected device.' }
    $Serial = $devices[0]
}
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Android test build failed.' }
    & $adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Application installation failed.' }
    & $adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
    # State-changing reboot, permission and Wi-Fi probes require explicit invocations; none is counted as a skipped pass here.
    $result = & $adb -s $Serial shell am instrument -w -e notClass com.chronoit.aivora.SchedulerRebootProbe,com.chronoit.aivora.SchedulerPermissionProbe,com.chronoit.aivora.WifiSettingsProbe,com.chronoit.aivora.MobileDataProbe,com.chronoit.aivora.LegacyWifiProbe,com.chronoit.aivora.DirectCallProbe,com.chronoit.aivora.ScheduledConnectionProbe,com.chronoit.aivora.SmsFrameworkProbe,com.chronoit.aivora.LocalReasoningProbe com.chronoit.aivora.test/androidx.test.runner.AndroidJUnitRunner
    $result | Write-Output
    $report = Join-Path (Get-Location) 'app/build/reports/android-instrumentation.txt'
    $result | Set-Content -LiteralPath $report -Encoding utf8
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(\d+ tests?\)' -or ($result -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') {
        throw 'Android instrumentation did not pass. See app/build/reports/android-instrumentation.txt.'
    }
} finally { Pop-Location }



