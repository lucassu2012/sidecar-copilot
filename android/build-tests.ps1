param([string]$SdkRoot='E:\tools\android-sdk',[string]$JdkRoot='E:\tools\jdk-17.0.19+10')
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$bt=Join-Path $SdkRoot 'build-tools\35.0.0'
$platform=Join-Path $SdkRoot 'platforms\android-35\android.jar'
$build=Join-Path $PSScriptRoot 'build'
foreach($name in @('instrumentation','test-dex')) {
    $target=Join-Path $build $name
    if(Test-Path -LiteralPath $target){$resolved=(Resolve-Path -LiteralPath $target).Path;if(!$resolved.StartsWith($PSScriptRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe generated path'};Remove-Item -LiteralPath $resolved -Recurse -Force}
}
New-Item -ItemType Directory -Force "$build\instrumentation","$build\test-dex" | Out-Null
function Check-Exit { if($LASTEXITCODE -ne 0){throw "Build tool failed: $LASTEXITCODE"} }
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$platform;$build\classes.jar" -d "$build\instrumentation" 'tests\StoreInstrumentation.java' 'tests\AudioSourceReferenceChecks.java' 'tests\BufferedCaptureChecks.java' 'tests\ControlRecoveryChecks.java' 'tests\RetentionCleanupChecks.java' 'tests\ClockQuarantineChecks.java' 'tests\NoteAnchorFailureChecks.java' 'tests\RecoveryProjectionChecks.java' 'tests\ReadCompletionChecks.java' 'tests\OwnedBufferChecks.java' 'tests\RangeBarrierChecks.java' 'tests\AtomicNoteFileChecks.java' 'tests\DeletionMarkerChecks.java' 'tests\PendingNoteCopyChecks.java' 'tests\MeetingIsolationChecks.java' 'tests\WalConfigurationChecks.java' 'tests\AudioStoreChecks.java' 'tests\CaptureServiceChecks.java' 'tests\MeetingSessionChecks.java' 'tests\AudioRetentionChecks.java' 'tests\NoteConcurrencyChecks.java' 'tests\ReplayAdmissionChecks.java' 'tests\NoteRetentionChecks.java' 'tests\UiResponsivenessChecks.java'
Check-Exit
& "$JdkRoot\bin\jar.exe" cf "$build\instrumentation.jar" -C "$build\instrumentation" .
Check-Exit
& "$bt\d8.bat" --lib $platform --classpath "$build\classes.jar" --min-api 30 --output "$build\test-dex" "$build\instrumentation.jar"
Check-Exit
& "$bt\aapt2.exe" link -I $platform --manifest 'tests\AndroidManifest.xml' -o "$build\test-unsigned.apk"
Check-Exit
& "$JdkRoot\bin\jar.exe" uf "$build\test-unsigned.apk" -C "$build\test-dex" classes.dex
Check-Exit
& "$bt\zipalign.exe" -f 4 "$build\test-unsigned.apk" "$build\test-aligned.apk"
Check-Exit
& "$bt\apksigner.bat" sign --ks "$build\debug.p12" --ks-pass pass:android --out "$build\sidecar-tests.apk" "$build\test-aligned.apk"
Check-Exit
