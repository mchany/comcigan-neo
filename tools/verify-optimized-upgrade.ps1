param(
    [string]$Adb = 'adb',
    [string]$Aapt = 'aapt',
    [string]$Serial = 'emulator-5554',
    [string]$BaselineApk = 'app/build/outputs/apk/debug/app-debug.apk',
    [string]$TestApk = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
    [string]$OptimizedApk = 'app/build/outputs/apk/release/app-release.apk',
    [string]$LogDirectory = 'work/optimized-upgrade',
    [switch]$LegacyReceipt
)
$ErrorActionPreference = 'Stop'
$target = 'io.github.mchany.comciganneo.smoke'
$component = "$target/io.github.mchany.comciganneo.MainActivity"
New-Item -ItemType Directory -Force $LogDirectory | Out-Null
function Adb-Checked([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments[0])" }
    return $output
}
function Read-Screen([string]$Name) {
    Adb-Checked @('shell','uiautomator','dump',"/sdcard/$Name.xml") | Out-Null
    $path = Join-Path $LogDirectory "$Name.xml"
    Adb-Checked @('pull',"/sdcard/$Name.xml",$path) | Out-Null
    return Get-Content -LiteralPath $path -Raw
}
if ((Adb-Checked @('shell','getprop','ro.kernel.qemu')).Trim() -ne '1') { throw 'Use an Android emulator.' }
foreach ($item in @(@($BaselineApk,$target), @($TestApk,"$target.test"), @($OptimizedApk,$target))) {
    $badging = & $Aapt dump badging $item[0] | Out-String
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch ("package: name='" + [regex]::Escape($item[1]) + "'")) {
        throw 'All APKs must target the isolated .smoke verification package.'
    }
    if ($item[0] -eq $OptimizedApk -and $badging.Contains('application-debuggable')) {
        throw 'The optimized APK must be a non-debuggable release build.'
    }
}
Adb-Checked @('install','-r','-d',$BaselineApk)
Adb-Checked @('install','-r',$TestApk)
# Only the isolated verification package is cleared; the user's normal app is preserved.
Adb-Checked @('shell','pm','clear',$target) | Out-Null
Adb-Checked @('shell','appwidget','grantbind','--package',$target,'--user','0') | Out-Null
Adb-Checked @('shell','pm','grant',$target,'android.permission.POST_NOTIFICATIONS') | Out-Null
Adb-Checked @('shell','appops','set',$target,'SCHEDULE_EXACT_ALARM','allow') | Out-Null
$prepareLog = Join-Path $LogDirectory 'prepare.log'
$legacyFlag = $LegacyReceipt.IsPresent.ToString().ToLowerInvariant()
& $Adb -s $Serial shell am instrument -w -e legacyReceipt $legacyFlag -e class 'io.github.mchany.comciganneo.BackgroundRestoreTest#prepareUpgrade' "$target.test/androidx.test.runner.AndroidJUnitRunner" *> $prepareLog
$prepared = Get-Content -LiteralPath $prepareLog -Raw
if ($LASTEXITCODE -ne 0 -or $prepared -notmatch 'OK \(1 test\)') { throw "Preparation failed; see $prepareLog" }
if ($LegacyReceipt -and $prepared -notmatch 'legacyDateReceiptVerified=true') { throw 'The version-6 receipt reproduction did not run.' }
$ids = [regex]::Match($prepared, 'restoreWorkIds=([a-f0-9,-]+)').Groups[1].Value.Split(',')
$oldPid = [regex]::Match($prepared, 'originalPid=(\d+)').Groups[1].Value
if ($ids.Count -ne 2 -or -not $oldPid) { throw 'Missing restoration evidence from preparation.' }
Adb-Checked @('logcat','-c') | Out-Null
Adb-Checked @('install','-r',$OptimizedApk)
# From here on no instrumentation is attached to the optimized app.
Adb-Checked @('shell','am','start','-W','-n',$component) | Out-Null
$newPid = (Adb-Checked @('shell','pidof',$target) | Out-String).Trim()
if (-not $newPid -or $newPid -eq $oldPid) { throw 'Expected a new app process after the APK upgrade.' }
if ((Read-Screen 'optimized-timetable') -notmatch '국어 김선생') { throw 'Optimized app could not restore the timetable cache.' }
Adb-Checked @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
$terminated = $false
foreach ($attempt in 1..10) {
    Adb-Checked @('shell','am','kill','--user','0',$target) | Out-Null
    Start-Sleep -Seconds 1
    $afterKill = (& $Adb -s $Serial shell pidof $target | Out-String).Trim()
    if ($afterKill -ne $newPid) { $terminated = $true; break }
}
if (-not $terminated) { throw 'The upgraded background process was still active; retry once startup jobs finish.' }
$deadline = [DateTime]::UtcNow.AddSeconds(210)
$workersFinished = $false
$notificationsPosted = $false
Write-Output 'Upgraded to the optimized APK; waiting for persisted workers and the scheduled alarm.'
do {
    $logs = Adb-Checked @('logcat','-d','-s','WM-WorkerWrapper:I','AndroidRuntime:E') | Out-String
    $workersFinished = @($ids | Where-Object { $logs -match ('Worker result SUCCESS[^\r\n]*' + [regex]::Escape($_)) }).Count -eq 2
    $notifications = Adb-Checked @('shell','dumpsys','notification','--noredact') | Out-String
    $active = [regex]::Match($notifications, '(?s)Notification List:(.*?)Snoozed notifications:').Groups[1].Value
    $own = ([regex]::Split($active, '(?=NotificationRecord\()') | Where-Object { $_ -match ('pkg=' + [regex]::Escape($target) + '\s') }) -join "`n"
    $notificationsPosted = $own.Contains('오늘의 시간표에요') -and $own.Contains('오늘의 급식이에요')
    if ($workersFinished -and $notificationsPosted) { break }
    Start-Sleep -Seconds 3
} while ([DateTime]::UtcNow -lt $deadline)
$logs | Set-Content -LiteralPath (Join-Path $LogDirectory 'background-workers.log') -Encoding utf8
if (-not $workersFinished -or -not $notificationsPosted) { throw 'Optimized background verification timed out; inspect background-workers.log.' }
Adb-Checked @('shell','am','start','-W','-f','0x24000000','-n',$component,'--es','notification_kind','meals','--es','notification_event','optimized-upgrade') | Out-Null
if ((Read-Screen 'optimized-meals') -notmatch '점심검증밥') { throw 'Optimized app could not restore the meal cache.' }
Adb-Checked @('shell','pm','clear',$target) | Out-Null
Adb-Checked @('shell','am','start','-W','-n',$component) | Out-Null
if ((Read-Screen 'optimized-fresh-setup') -notmatch '초기 설정 · 1/3') { throw 'Optimized first-install setup was not displayed.' }
[pscustomobject]@{ oldProcess=$oldPid; upgradedProcess=$newPid; backgroundProcessTerminated=$terminated; restoredWorkIds=$ids; workersSucceeded=$workersFinished; bothNotificationsPosted=$notificationsPosted; timetableAndMealCachesRestored=$true; freshSetupDisplayed=$true; legacyDateReceiptMigrationVerified=$LegacyReceipt.IsPresent } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LogDirectory 'result.json') -Encoding utf8
Write-Output 'PASS: real R8 APK upgrade, cold background worker restoration, scheduled notifications, and cached UI.'
