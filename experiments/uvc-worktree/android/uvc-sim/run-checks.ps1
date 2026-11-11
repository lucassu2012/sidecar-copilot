param([string]$JdkRoot='E:\tools\jdk-17.0.19+10', [string]$SdkRoot='E:\tools\android-sdk')
$ErrorActionPreference='Stop'
$outputDir=Join-Path (Join-Path $PSScriptRoot 'build') ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$sources=@(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
$checks=@(Get-ChildItem (Join-Path $PSScriptRoot 'test') -Filter '*.java' -Recurse | ForEach-Object FullName)
& "$JdkRoot\bin\javac.exe" --release 8 -encoding UTF-8 -d $outputDir @sources @checks
if($LASTEXITCODE -ne 0){throw 'JVM compilation failed'}
foreach($check in @('CoordinatorChecks','local.sidecar.copilot.uvcsim.FramePipelineChecks','local.sidecar.copilot.uvcsim.IntegrationChecks')) {
    & "$JdkRoot\bin\java.exe" -cp $outputDir $check
    if($LASTEXITCODE -ne 0){throw "$check failed"}
}
$discovery=Join-Path $PSScriptRoot 'android\local\sidecar\copilot\uvc\AndroidUvcDiscovery.java'
& "$JdkRoot\bin\javac.exe" --release 8 -encoding UTF-8 -classpath (Join-Path $SdkRoot 'platforms\android-35\android.jar') -d $outputDir $discovery
if($LASTEXITCODE -ne 0){throw 'Android discovery compilation failed'}
Write-Output 'PASS Android discovery API 35 compilation only; no device used.'
