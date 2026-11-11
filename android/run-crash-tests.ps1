param(
    [string]$SdkRoot='E:\tools\android-sdk',
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$AvdName,
    [Parameter(Mandatory=$true)][string]$TestPin,
    [string[]]$Cases=@('before_first_frame','memory_tail','after_fsync','before_index_commit',
        'after_pause_barrier','after_pause_finish','overflow_pause_barrier','deleted','recovery_transaction','legacy_active')
)
$ErrorActionPreference='Stop'
$adb=Join-Path $SdkRoot 'platform-tools\adb.exe'
if($Serial -notmatch '^emulator-[0-9]+$'){throw 'Disposable emulator serial required'}
$hardware=(& $adb -s $Serial shell getprop ro.hardware | Out-String).Trim()
$name=(& $adb -s $Serial emu avd name | Select-Object -First 1).Trim()
if($hardware -ne 'ranchu' -or $name -ne $AvdName){throw 'Unexpected emulator: refusing to install or terminate processes'}
if((& $adb -s $Serial shell dumpsys trust | Out-String) -notmatch 'deviceLocked=0'){throw 'Unlock the disposable emulator with its test credential first'}
$logs=Join-Path $PSScriptRoot 'build\crash-results'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
foreach($apk in @('sidecar-dev.apk','crash-tests.apk')){
    $installed=& $adb -s $Serial install -r (Join-Path $PSScriptRoot "build\$apk") 2>&1 | Out-String
    if($LASTEXITCODE -ne 0 -or $installed -notmatch 'Success'){throw $installed}
}
$component='local.sidecar.copilot.crashtest/local.sidecar.copilot.CrashFaultInstrumentation'
foreach($scenario in $Cases){
    # Refresh only the supplied synthetic credential; this does not dismiss or bypass keyguard.
    $auth=& $adb -s $Serial shell locksettings verify --old $TestPin 2>&1 | Out-String
    if($LASTEXITCODE -ne 0 -or $auth -notmatch 'verified'){throw 'Test credential verification failed'}
    foreach($phase in @('crash')+$(if($scenario -eq 'recovery_transaction'){@('recover-crash')}else{@()})+@('verify')){
        $output=& $adb -s $Serial shell am instrument -w -r -e phase $phase -e scenario $scenario $component 2>&1 | Out-String
        $output | Set-Content -Encoding UTF8 (Join-Path $logs "$scenario-$phase.log")
        if($phase -eq 'verify'){
            if($output -notmatch "PASS ${scenario}:" -or $output -notmatch 'INSTRUMENTATION_CODE: -1' -or $output -match 'FAILED'){throw "$scenario verify failed: $output"}
            Write-Output ($output -split "`n" | Where-Object {$_ -match "PASS ${scenario}:"})
        }else{
            if($output -notmatch "CRASH_CHECKPOINT $scenario " -or $output -notmatch 'Process crashed'){throw "$scenario did not reach its abrupt termination checkpoint: $output"}
        }
    }
}
Write-Output "PASS: $($Cases.Count) synthetic abrupt-process scenarios. No physical capture or playback."
