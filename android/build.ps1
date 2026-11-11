param([string]$SdkRoot = 'E:\tools\android-sdk', [string]$JdkRoot = 'E:\tools\jdk-17.0.19+10')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$build = Join-Path $PSScriptRoot 'build'
$bt = Join-Path $SdkRoot 'build-tools\35.0.0'
$platform = Join-Path $SdkRoot 'platforms\android-35\android.jar'
foreach($name in @('classes','dex','tests')) {
    $target=Join-Path $build $name
    if(Test-Path -LiteralPath $target){$resolved=(Resolve-Path -LiteralPath $target).Path;if(!$resolved.StartsWith($PSScriptRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe generated path'};Remove-Item -LiteralPath $resolved -Recurse -Force}
}
New-Item -ItemType Directory -Force -Path $build,"$build\classes","$build\dex","$build\tests" | Out-Null
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $LASTEXITCODE" } }
$sources = @(Get-ChildItem 'app\src\main\java' -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $platform -d "$build\classes" @sources
Check-Exit
& "$JdkRoot\bin\jar.exe" cf "$build\classes.jar" -C "$build\classes" .
Check-Exit
$fixtureClasses=& "$JdkRoot\bin\jar.exe" tf "$build\classes.jar" | Select-String 'Fixture|Instrumentation|SyntheticSource'
Check-Exit
if($fixtureClasses){throw 'Fixture classes must not be included in the development app'}
& "$bt\d8.bat" --lib $platform --min-api 30 --output "$build\dex" "$build\classes.jar"
Check-Exit
& "$bt\aapt2.exe" compile --dir 'app\src\main\res' -o "$build\app-resources.zip"
Check-Exit
& "$bt\aapt2.exe" link -I $platform --manifest 'app\src\main\AndroidManifest.xml' -o "$build\unsigned.apk" "$build\app-resources.zip"
Check-Exit
& "$JdkRoot\bin\jar.exe" uf "$build\unsigned.apk" -C "$build\dex" classes.dex
Check-Exit
& "$bt\zipalign.exe" -f 4 "$build\unsigned.apk" "$build\aligned.apk"
Check-Exit
if (!(Test-Path "$build\debug.p12")) {
    & "$JdkRoot\bin\keytool.exe" -genkeypair -keystore "$build\debug.p12" -storepass android -keypass android -alias androiddebugkey -dname 'CN=Sidecar Local Development' -keyalg RSA -keysize 2048 -validity 3650
    Check-Exit
}
& "$bt\apksigner.bat" sign --ks "$build\debug.p12" --ks-pass pass:android --out "$build\sidecar-dev.apk" "$build\aligned.apk"
Check-Exit
& "$bt\apksigner.bat" verify --verbose "$build\sidecar-dev.apk"
Check-Exit
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d "$build\tests" 'app\src\main\java\local\sidecar\copilot\NotePolicy.java' 'app\src\main\java\local\sidecar\copilot\RetentionClock.java' 'tests\NotePolicyTest.java'
Check-Exit
& "$JdkRoot\bin\java.exe" -cp "$build\tests" NotePolicyTest
Check-Exit
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d "$build\tests" 'app\src\main\java\local\sidecar\copilot\PcmFrameAssembler.java' 'tests\PcmFrameAssemblerTest.java'
Check-Exit
& "$JdkRoot\bin\java.exe" -cp "$build\tests" PcmFrameAssemblerTest
Check-Exit
Get-FileHash "$build\sidecar-dev.apk" -Algorithm SHA256
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d "$build\tests" 'app\src\main\java\local\sidecar\copilot\CaptureStartGate.java' 'tests\CaptureStartGateTest.java'
Check-Exit
& "$JdkRoot\bin\java.exe" -cp "$build\tests" CaptureStartGateTest
Check-Exit
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d "$build\tests" 'app\src\main\java\local\sidecar\copilot\PcmSourceLifecycle.java' 'tests\PcmSourceLifecycleTest.java'
Check-Exit
& "$JdkRoot\bin\java.exe" -cp "$build\tests" PcmSourceLifecycleTest
Check-Exit

& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d "$build\tests" 'app\src\main\java\local\sidecar\copilot\NotePolicy.java' 'app\src\main\java\local\sidecar\copilot\RetentionEnvelope.java' 'tests\RetentionEnvelopeTest.java'
Check-Exit
& "$JdkRoot\bin\java.exe" -cp "$build\tests" RetentionEnvelopeTest
Check-Exit

& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d "$build\tests" 'app\src\main\java\local\sidecar\copilot\BoundedPcmQueue.java' 'tests\BoundedPcmQueueTest.java'
Check-Exit
& "$JdkRoot\bin\java.exe" -cp "$build\tests" BoundedPcmQueueTest
Check-Exit

& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d "$build\tests" 'app\src\main\java\local\sidecar\copilot\NoteFileSet.java' 'tests\NoteFileSetTest.java'
Check-Exit
& "$JdkRoot\bin\java.exe" -cp "$build\tests" local.sidecar.copilot.NoteFileSetTest
Check-Exit
