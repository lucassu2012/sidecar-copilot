param([string]$JdkRoot='E:\tools\jdk-17.0.19+10', [string]$SdkRoot='E:\tools\android-sdk')
$ErrorActionPreference='Stop'
$outputDir=Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$sources=@(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
& "$JdkRoot\bin\javac.exe" --release 8 -encoding UTF-8 -d $outputDir @sources (Join-Path $PSScriptRoot 'test\UvcLifecycleChecks.java')
if($LASTEXITCODE -ne 0){throw 'JVM compilation failed'}
& "$JdkRoot\bin\java.exe" -cp $outputDir UvcLifecycleChecks
if($LASTEXITCODE -ne 0){throw 'Lifecycle checks failed'}
& "$JdkRoot\bin\javac.exe" --release 8 -encoding UTF-8 -classpath (Join-Path $SdkRoot 'platforms\android-35\android.jar') -d $outputDir (Join-Path $PSScriptRoot 'android\local\sidecar\copilot\uvc\AndroidUvcDiscovery.java')
if($LASTEXITCODE -ne 0){throw 'Android discovery compilation failed'}
Write-Output 'Android discovery compiled against API 35; no device invocation.'
