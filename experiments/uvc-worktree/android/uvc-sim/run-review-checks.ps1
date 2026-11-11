param(
    [string]$JdkRoot = 'E:\tools\jdk-17.0.19+10',
    [string]$SdkRoot = 'E:\tools\android-sdk',
    [string]$EvidenceDir = (Join-Path $PSScriptRoot 'review-evidence')
)
$ErrorActionPreference = 'Stop'
$outputDir = Join-Path (Join-Path $PSScriptRoot 'build') ('review-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $outputDir,$evidenceDir | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Invoke-RecordedCheck {
    param([string]$Name, [string]$Command, [string[]]$CommandArguments)
    $oldPreference = $ErrorActionPreference
    try {
        # Java assertion failures are evidence, not a reason to skip remaining checks.
        $ErrorActionPreference = 'Continue'
        $lines = @(& $Command @CommandArguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    [System.IO.File]::WriteAllLines((Join-Path $evidenceDir ($Name + '.txt')), [string[]]$lines, $utf8)
    $results.Add([pscustomobject]@{ name = $Name; exitCode = $code; log = $Name + '.txt' })
    Write-Output ($Name + ': exit ' + $code)
    $lines | Write-Output
    return
}

$shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
Invoke-RecordedCheck -Name 'baseline' -Command $shell -CommandArguments @(
    '-NoProfile', '-File', (Join-Path $PSScriptRoot 'run-checks.ps1'), '-JdkRoot', $JdkRoot, '-SdkRoot', $SdkRoot
)
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
$checks = @(Get-ChildItem (Join-Path $PSScriptRoot 'review-tests') -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-RecordedCheck -Name 'review-compilation' -Command "$JdkRoot\bin\javac.exe" -CommandArguments (@(
    '--release', '8', '-encoding', 'UTF-8', '-d', $outputDir
) + $sources + $checks)
if ($results[$results.Count - 1].exitCode -eq 0) {
    $programs = [ordered]@{
        'coordinator-review' = 'CoordinatorReviewChecks'
        'pipeline-review' = 'local.sidecar.copilot.uvcsim.PipelineReviewChecks'
        'frame-order-regression' = 'local.sidecar.copilot.uvcsim.FrameOrderChecks'
    }
    foreach ($entry in $programs.GetEnumerator()) {
        Invoke-RecordedCheck -Name $entry.Key -Command "$JdkRoot\bin\java.exe" -CommandArguments @('-cp', $outputDir, $entry.Value)
    }
    Invoke-RecordedCheck -Name 'pipeline-safety-review' -Command "$JdkRoot\bin\java.exe" -CommandArguments @(
        '-cp', $outputDir, 'local.sidecar.copilot.uvcsim.PipelineReviewChecks', '--safety'
    )
}
$sourceHead = (& git -C $PSScriptRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot record source HEAD' }
& git -C $PSScriptRoot diff --quiet b793449047021f102ec2ce59cebd9025cb57977b -- src test android
$baselineSourceUnchanged = $LASTEXITCODE -eq 0
$changedCode = @(& git -C $PSScriptRoot status --porcelain -- src test android review-tests run-review-checks.ps1)
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify code worktree status' }
$report = [ordered]@{
    reviewedCommit = 'b793449047021f102ec2ce59cebd9025cb57977b'
    sourceHeadAtRun = $sourceHead
    baselineSourceUnchanged = $baselineSourceUnchanged
    sourceWorktreeClean = $changedCode.Count -eq 0
    recordedAtUtc = [DateTime]::UtcNow.ToString('o')
    jdkRoot = $JdkRoot
    sdkRoot = $SdkRoot
    results = @($results.ToArray())
    note = 'Nonzero review exits remain failures; no expected-failure conversion. Synthetic JVM tests and Android API compile only.'
}
[System.IO.File]::WriteAllText((Join-Path $evidenceDir 'checks.json'), ($report | ConvertTo-Json -Depth 5), $utf8)
if (@($results | Where-Object { $_.exitCode -ne 0 }).Count -gt 0) { exit 1 }
exit 0
