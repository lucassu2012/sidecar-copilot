param([string]$JdkRoot='E:\tools\jdk-17.0.19+10')
$ErrorActionPreference='Stop'
$outputDir=Join-Path (Join-Path $PSScriptRoot 'build') ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$inputs=@(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java')
$inputs+=@(Get-ChildItem (Join-Path $PSScriptRoot 'test') -Recurse -Filter '*.java')
$inputs+=@(Get-Item -LiteralPath $PSCommandPath)
$before=@($inputs | Sort-Object FullName | ForEach-Object {
    [ordered]@{path=$_.FullName; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
})
$before | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputDir 'inputs.json') -Encoding utf8
$sourcePaths=@($inputs | Where-Object Extension -EQ '.java' | ForEach-Object FullName)
$compile=@(& "$JdkRoot\bin\javac.exe" --release 8 -encoding UTF-8 -Xlint:all -Werror -d $outputDir @sourcePaths 2>&1)
$compileExit=$LASTEXITCODE
$compile | Out-File (Join-Path $outputDir 'compile.txt') -Encoding utf8
$compile | Write-Output
[ordered]@{executable="$JdkRoot\bin\javac.exe"; arguments=@('--release','8','-encoding','UTF-8','-Xlint:all','-Werror','-d',$outputDir)+$sourcePaths; exit_code=$compileExit} |
    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputDir 'compile-receipt.json') -Encoding utf8
if($compileExit -ne 0){throw "Contract compilation failed: $outputDir"}
$run=@(& "$JdkRoot\bin\java.exe" -cp $outputDir local.sidecar.copilot.uvccontract.ContractChecks 2>&1)
$runExit=$LASTEXITCODE
$run | Out-File (Join-Path $outputDir 'checks.txt') -Encoding utf8
$run | Write-Output
foreach($entry in $before) {
    if((Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw "Contract input changed during verification: $($entry.path)"
    }
}
[ordered]@{compile_exit=$compileExit; run_exit=$runExit; utc=[DateTime]::UtcNow.ToString('o');
    executable="$JdkRoot\bin\java.exe"; arguments=@('-cp',$outputDir,'local.sidecar.copilot.uvccontract.ContractChecks');
    java=(& "$JdkRoot\bin\java.exe" -version 2>&1 | Out-String).Trim();
    scope='Java 8 host contract checks only; no Android/JNI/USB'} |
    ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outputDir 'receipt.json') -Encoding utf8
Write-Output "Evidence: $outputDir"
if($runExit -ne 0){throw 'Contract checks failed'}
