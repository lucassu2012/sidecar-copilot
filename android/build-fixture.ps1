param([string]$SdkRoot='E:\tools\android-sdk',[string]$JdkRoot='E:\tools\jdk-17.0.19+10')
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$build=Join-Path $PSScriptRoot 'build'
$bt=Join-Path $SdkRoot 'build-tools\35.0.0'
$platform=Join-Path $SdkRoot 'platforms\android-35\android.jar'
function Check-Exit {if($LASTEXITCODE -ne 0){throw "Fixture build failed: $LASTEXITCODE"}}
foreach($name in @('fixture-classes','fixture-dex','fixture-test-classes','fixture-test-dex')) {
    $target=Join-Path $build $name
    if(Test-Path -LiteralPath $target){$resolved=(Resolve-Path -LiteralPath $target).Path;if(!$resolved.StartsWith((Resolve-Path $build).Path+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe generated path'};Remove-Item -LiteralPath $resolved -Recurse -Force}
    New-Item -ItemType Directory -Force $target | Out-Null
}
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$platform;$build\classes.jar" -d "$build\fixture-classes" 'fixtures\FixtureCaptureService.java'
Check-Exit
Copy-Item -LiteralPath "$build\classes.jar" -Destination "$build\fixture.jar" -Force
& "$JdkRoot\bin\jar.exe" uf "$build\fixture.jar" -C "$build\fixture-classes" .
Check-Exit
& "$bt\d8.bat" --lib $platform --min-api 30 --output "$build\fixture-dex" "$build\fixture.jar"
Check-Exit
& "$bt\aapt2.exe" compile --dir 'app\src\main\res' -o "$build\fixture-resources.zip"
Check-Exit
& "$bt\aapt2.exe" link -I $platform --manifest 'fixtures\AndroidManifest.xml' -o "$build\fixture-unsigned.apk" "$build\fixture-resources.zip"
Check-Exit
& "$JdkRoot\bin\jar.exe" uf "$build\fixture-unsigned.apk" -C "$build\fixture-dex" classes.dex
Check-Exit
& "$bt\zipalign.exe" -f 4 "$build\fixture-unsigned.apk" "$build\fixture-aligned.apk"
Check-Exit
& "$bt\apksigner.bat" sign --ks "$build\debug.p12" --ks-pass pass:android --out "$build\sidecar-fixture.apk" "$build\fixture-aligned.apk"
Check-Exit
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$platform;$build\fixture.jar" -d "$build\fixture-test-classes" 'fixtures\FixtureInstrumentation.java' 'fixtures\UiPrivacyInstrumentation.java' 'fixtures\InterruptionDiagnosticInstrumentation.java' 'fixtures\ControlUiInstrumentation.java'
Check-Exit
& "$JdkRoot\bin\jar.exe" cf "$build\fixture-tests.jar" -C "$build\fixture-test-classes" .
Check-Exit
& "$bt\d8.bat" --lib $platform --classpath "$build\fixture.jar" --min-api 30 --output "$build\fixture-test-dex" "$build\fixture-tests.jar"
Check-Exit
& "$bt\aapt2.exe" link -I $platform --manifest 'fixtures\TestManifest.xml' -o "$build\fixture-test-unsigned.apk"
Check-Exit
& "$JdkRoot\bin\jar.exe" uf "$build\fixture-test-unsigned.apk" -C "$build\fixture-test-dex" classes.dex
Check-Exit
& "$bt\zipalign.exe" -f 4 "$build\fixture-test-unsigned.apk" "$build\fixture-test-aligned.apk"
Check-Exit
& "$bt\apksigner.bat" sign --ks "$build\debug.p12" --ks-pass pass:android --out "$build\sidecar-fixture-tests.apk" "$build\fixture-test-aligned.apk"
Check-Exit
