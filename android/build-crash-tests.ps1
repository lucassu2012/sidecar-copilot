param([string]$SdkRoot='E:\tools\android-sdk',[string]$JdkRoot='E:\tools\jdk-17.0.19+10')
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$bt=Join-Path $SdkRoot 'build-tools\35.0.0'
$platform=Join-Path $SdkRoot 'platforms\android-35\android.jar'
$build=Join-Path $PSScriptRoot 'build'
foreach($name in @('crash-instrumentation','crash-test-dex')) {
    $target=Join-Path $build $name
    if(Test-Path -LiteralPath $target){$resolved=(Resolve-Path -LiteralPath $target).Path;if(!$resolved.StartsWith($PSScriptRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe generated path'};Remove-Item -LiteralPath $resolved -Recurse -Force}
}
New-Item -ItemType Directory -Force "$build\crash-instrumentation","$build\crash-test-dex" | Out-Null
function Check-Exit { if($LASTEXITCODE -ne 0){throw "Build tool failed: $LASTEXITCODE"} }
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$platform;$build\classes.jar" -d "$build\crash-instrumentation" 'tests\CrashFaultInstrumentation.java'
Check-Exit
& "$JdkRoot\bin\jar.exe" cf "$build\crash-instrumentation.jar" -C "$build\crash-instrumentation" .
Check-Exit
& "$bt\d8.bat" --lib $platform --classpath "$build\classes.jar" --min-api 30 --output "$build\crash-test-dex" "$build\crash-instrumentation.jar"
Check-Exit
& "$bt\aapt2.exe" link -I $platform --manifest 'tests\CrashFaultManifest.xml' -o "$build\crash-test-unsigned.apk"
Check-Exit
& "$JdkRoot\bin\jar.exe" uf "$build\crash-test-unsigned.apk" -C "$build\crash-test-dex" classes.dex
Check-Exit
& "$bt\zipalign.exe" -f 4 "$build\crash-test-unsigned.apk" "$build\crash-test-aligned.apk"
Check-Exit
& "$bt\apksigner.bat" sign --ks "$build\debug.p12" --ks-pass pass:android --out "$build\crash-tests.apk" "$build\crash-test-aligned.apk"
Check-Exit

