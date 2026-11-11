#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CandidateRoot,
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$AvdName,
    [Parameter(Mandatory=$true)][string]$TestPin,
    [ValidateSet('Combined','Revocation','All')][string]$Mode='All',
    [string]$SdkRoot='E:\tools\android-sdk',
    [string]$EvidenceRoot,
    [string]$CoordinationRef,
    [switch]$Execute,
    [switch]$DisposableEmulator
)
# File-only by default. Execute is restricted to the explicitly owned disposable ranchu AVD.
# Never launches/stops an AVD, grants permissions, clears data, changes credentials, or force-stops apps.
# Instrumentation uses synthetic input. Its existing assertion deadlines are not changed by host deadlines.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-[0-9]+$'){throw 'A named disposable emulator serial is required; physical devices are refused'}
if($AvdName -notmatch '^[A-Za-z0-9_.-]+$'){throw 'An explicit safe AVD name is required'}
if($TestPin -notmatch '^[0-9]{4,16}$'){throw 'Supply the existing synthetic emulator PIN as 4 to 16 digits'}
if($Execute -and (!$DisposableEmulator -or [string]::IsNullOrWhiteSpace($CoordinationRef))){throw 'Execute requires DisposableEmulator and a recorded integration resource CoordinationRef'}
$root=(Resolve-Path -LiteralPath $CandidateRoot).Path
$source=Join-Path $root 'source'
$build=Join-Path $source 'build'
foreach($directory in @($root,$source,$build)){
    $info=Get-Item -LiteralPath $directory
    if(!$info.PSIsContainer -or ($info.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Candidate layout must use real directories without reparse points'}
}
$utf8=[Text.UTF8Encoding]::new($false)
function Write-Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 16),$utf8)}
function Utc { [DateTime]::UtcNow.ToString('o') }
function Confined-File([string]$Base,[string]$Relative){
    if([string]::IsNullOrWhiteSpace($Relative) -or [IO.Path]::IsPathRooted($Relative) -or $Relative.Contains(':')){throw 'Manifest path must be relative'}
    $path=[IO.Path]::GetFullPath((Join-Path $Base $Relative))
    if(!$path.StartsWith($Base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Manifest path escapes its candidate root'}
    $node=Get-Item -LiteralPath $path
    if($node.PSIsContainer){throw 'Manifest entry must be a regular file'}
    for($part=$node;$null -ne $part -and $part.FullName.Length -ge $Base.Length;){
        if(($part.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Candidate reparse points are refused'}
        $part=$(if($part -is [IO.FileInfo]){$part.Directory}else{$part.Parent})
    }
    return $path
}
$inputManifest=Join-Path $root 'inputs.json';$artifactManifest=Join-Path $root 'artifacts.json'
$inputManifestHash=(Get-FileHash -LiteralPath $inputManifest -Algorithm SHA256).Hash
$artifactManifestHash=(Get-FileHash -LiteralPath $artifactManifest -Algorithm SHA256).Hash
$inputRows=@(Get-Content -LiteralPath $inputManifest -Raw|ConvertFrom-Json)
$artifactRows=@(Get-Content -LiteralPath $artifactManifest -Raw|ConvertFrom-Json)
if($inputRows.Count -eq 0){throw 'Empty input manifest'}
$inputIndex=@{};$sourceRows=@()
foreach($row in $inputRows){
    $path=Confined-File $source ([string]$row.path)
    $relative=[IO.Path]::GetRelativePath($source,$path)
    if($relative.StartsWith('build'+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Source input manifest must not substitute generated build output for source'}
    if($inputIndex.ContainsKey($relative)){throw "Duplicate source manifest entry: $relative"}
    if([string]$row.sha256 -notmatch '^[0-9A-Fa-f]{64}$'){throw 'Malformed source SHA256'}
    $hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if($hash -ne $row.sha256){throw "Source hash mismatch: $relative"}
    $inputIndex[$relative]=$hash;$sourceRows+=@{path=$relative;sha256=$hash;absolute=$path}
}
# All files outside source/build must be inventoried, including every runner and manifest.
foreach($file in Get-ChildItem -LiteralPath $source -File -Recurse){
    $relative=[IO.Path]::GetRelativePath($source,$file.FullName)
    if(!$relative.StartsWith('build'+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -and !$inputIndex.ContainsKey($relative)){throw "Unmanifested candidate source: $relative"}
}
foreach($required in @('fixtures\ControlUiInstrumentation.java','fixtures\TestManifest.xml','fixtures\AndroidManifest.xml','build-fixture.ps1','run-control-tests.ps1')){
    if(!$inputIndex.ContainsKey($required)){throw "Required runner input absent: $required"}
}
if((Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash -ne $inputIndex['run-control-tests.ps1']){throw 'Executing orchestration script does not match the frozen candidate'}
$expected=@{
    'sidecar-dev.apk'='local.sidecar.copilot';'sidecar-tests.apk'='local.sidecar.copilot.test';
    'sidecar-fixture.apk'='local.sidecar.copilot.fixture';'sidecar-fixture-tests.apk'='local.sidecar.copilot.fixture.test';
    'crash-tests.apk'='local.sidecar.copilot.crashtest'
}
if($artifactRows.Count -ne 5){throw 'Exactly five frozen APK manifest entries are required'}
$artifacts=@{};$artifactEvidence=@()
foreach($row in $artifactRows){
    $name=[string]$row.File
    if(!$expected.ContainsKey($name) -or $artifacts.ContainsKey($name)){throw 'Unknown or duplicate APK manifest entry'}
    if([string]$row.Hash -notmatch '^[0-9A-Fa-f]{64}$' -or $row.SignatureVerified -isnot [bool] -or !$row.SignatureVerified){throw 'APK must have a SHA256 and a true SignatureVerified build receipt'}
    $path=Confined-File $build $name;$hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if($hash -ne $row.Hash){throw "APK hash mismatch: $name"}
    $artifacts[$name]=@{file=$name;package=$expected[$name];sha256=$hash;path=$path}
    $artifactEvidence+=@{file=$name;package=$expected[$name];sha256=$hash;signature_verified_build_receipt=$true}
}
if(!$Execute){Write-Output 'PASS local manifests: all source inputs and five APK hashes match. No device contacted; no runtime acceptance claimed.';return}
if([string]::IsNullOrWhiteSpace($EvidenceRoot)){throw 'Execute requires an explicit EvidenceRoot'}
$evidenceFull=[IO.Path]::GetFullPath($EvidenceRoot)
if($evidenceFull.Equals($root,[StringComparison]::OrdinalIgnoreCase) -or $evidenceFull.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must be outside the frozen candidate'}
$adb=Join-Path $SdkRoot 'platform-tools\adb.exe'
if(!(Test-Path -LiteralPath $adb -PathType Leaf)){throw 'ADB executable unavailable'}
$aapt=Join-Path $SdkRoot 'build-tools\35.0.0\aapt.exe'
if(!(Test-Path -LiteralPath $aapt -PathType Leaf)){throw 'API 35 aapt is required for exact APK package checks'}
$runId=[guid]::NewGuid().ToString();$run=Join-Path $evidenceFull (([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))+'-control-'+$runId)
New-Item -ItemType Directory -Path $run -ErrorAction Stop|Out-Null
New-Item -ItemType Directory -Path (Join-Path $run 'inputs'),(Join-Path $run 'device')|Out-Null
$component='local.sidecar.copilot.fixture.test/local.sidecar.copilot.ControlUiInstrumentation'
$target='local.sidecar.copilot.fixture'
$receipt=[ordered]@{run_id=$runId;started_utc=(Utc);mode=$Mode;serial=$Serial;avd=$AvdName;coordination_ref=$CoordinationRef;component=$component;
    scope='disposable ranchu emulator; synthetic input only';complete=$false;passed=$false;failure=$null;terminal='preflight';
    inputs_manifest_sha256=$inputManifestHash;artifacts_manifest_sha256=$artifactManifestHash;artifacts=$artifactEvidence;commands=@();
    boot_id=$null;revocation_issued=$false;revocation_verified=$false;combined_verified=$false;postflight_errors=@()}
$jobs=[Collections.Generic.List[object]]::new();$commandNumber=0;$identityVerified=$false;$installed=$false
function Start-Adb([string]$Label,[string[]]$Arguments,[switch]$Secret){
    $script:commandNumber++
    $prefix=('{0:D3}-{1}' -f $script:commandNumber,$Label)
    $stdout=Join-Path $run ($prefix+'.stdout.log');$stderr=Join-Path $run ($prefix+'.stderr.log')
    # No shell is used. Reject quote/newline-bearing arguments rather than composing shell syntax.
    foreach($argument in (@('-s',$Serial)+$Arguments)){if($argument -match '["\r\n]'){throw 'Unsupported command argument'}}
    $quoted=(@('-s',$Serial)+$Arguments|ForEach-Object {'"'+$_+'"'}) -join ' '
    $record=[ordered]@{label=$Label;arguments=$(if($Secret){@('shell','locksettings','verify','--old','[REDACTED]')}else{$Arguments});started_utc=(Utc);ended_utc=$null;exit_code=$null;stdout=[IO.Path]::GetFileName($stdout);stderr=[IO.Path]::GetFileName($stderr);stdout_sha256=$null;stderr_sha256=$null;host_adb_terminated=$false}
    $receipt.commands+=,$record
    $process=Start-Process -FilePath $adb -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $job=@{process=$process;record=$record;stdout=$stdout;stderr=$stderr;finished=$false};$jobs.Add($job);return $job
}
function Read-RedirectedLog([string]$Path,[bool]$Complete){
    $stream=$null;$reader=$null
    try{
        try{$stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))}
        catch [IO.FileNotFoundException]{return ''}
        catch [IO.DirectoryNotFoundException]{return ''}
        $reader=[IO.StreamReader]::new($stream,[Text.UTF8Encoding]::new($false),$true)
        $value=$reader.ReadToEnd()
        # A live redirected writer may stop at any byte, including inside UTF-8 or a READY line.
        # Only complete lines may reach the polling parser; completed commands retain their tail.
        if(!$Complete){$lastNewline=$value.LastIndexOf("`n");if($lastNewline -lt 0){return ''};return $value.Substring(0,$lastNewline+1)}
        return $value
    }finally{if($null -ne $reader){$reader.Dispose()}elseif($null -ne $stream){$stream.Dispose()}}
}
function Read-Adb($Job){
    $out=Read-RedirectedLog $Job.stdout ([bool]$Job.finished)
    $err=Read-RedirectedLog $Job.stderr ([bool]$Job.finished)
    return $out+"`n"+$err
}
function Complete-Adb($Job,[int]$TimeoutSeconds=30,[switch]$AllowFailure){
    if(!$Job.process.WaitForExit($TimeoutSeconds*1000)){throw "Host deadline exceeded: $($Job.record.label); device work may still be active"}
    $Job.process.Refresh();$Job.record.exit_code=$Job.process.ExitCode;$Job.record.ended_utc=Utc
    $Job.record.stdout_sha256=(Get-FileHash -LiteralPath $Job.stdout -Algorithm SHA256).Hash
    $Job.record.stderr_sha256=(Get-FileHash -LiteralPath $Job.stderr -Algorithm SHA256).Hash;$Job.finished=$true
    $output=Read-Adb $Job
    if(!$AllowFailure -and $Job.record.exit_code -ne 0){throw "ADB failed: $($Job.record.label), exit $($Job.record.exit_code)"}
    return $output
}
function Adb([string]$Label,[string[]]$Arguments,[int]$TimeoutSeconds=30,[switch]$AllowFailure,[switch]$Secret){
    $job=Start-Adb $Label $Arguments -Secret:$Secret
    $output=Complete-Adb $job $TimeoutSeconds -AllowFailure:$AllowFailure
    return @{output=$output;exit_code=$job.record.exit_code}
}
function Event-Json([string]$Output,[string]$Name){
    # The final RESULT replays the runner's log. Identity is taken only from its live STATUS event.
    $events=[regex]::Matches($Output,'(?m)^INSTRUMENTATION_STATUS: stream='+[regex]::Escape($Name)+' (\{[^\r\n]+\})\r?$')
    if($events.Count -ne 1){throw "Expected exactly one $Name identity event; got $($events.Count)"}
    return ($events[0].Groups[1].Value|ConvertFrom-Json)
}
function Require-Environment([string]$Output,[string]$Phase){
    $envEvent=Event-Json $Output 'CONTROL_ENVIRONMENT'
    if($envEvent.run_id -ne $runId -or $envEvent.phase -ne $Phase -or $envEvent.boot_id -ne $receipt.boot_id -or [long]$envEvent.pid -le 0 -or [long]$envEvent.process_start_elapsed_ms -le 0 -or $envEvent.process_token -notmatch '^[0-9a-f-]{36}$'){throw 'Instrumentation environment identity mismatch'}
    if($envEvent.channel_present -and [int]$envEvent.channel_importance -lt 2){throw 'Existing capture channel is blocked; do not silently recreate it'}
    Write-Json (Join-Path $run ($Phase+'-environment.json')) $envEvent
    return $envEvent
}
function Permission-State([string]$Label){
    $dump=(Adb $Label @('shell','dumpsys','package',$target)).output
    $values=@{}
    foreach($permission in @('RECORD_AUDIO','POST_NOTIFICATIONS')){
        $hits=[regex]::Matches($dump,'android\.permission\.'+$permission+': granted=(true|false)')
        if($hits.Count -ne 1){throw "Ambiguous or unavailable fixture permission: $permission"}
        $values[$permission]=($hits[0].Groups[1].Value -eq 'true')
    }
    return $values
}
function No-Service([string]$Label){
    $out=(Adb $Label @('shell','dumpsys','activity','services',$target)).output
    if($out -match ('ServiceRecord\{[^\r\n]*'+[regex]::Escape($target)+'/')){throw 'Fixture service is already active; refusing to install or start another runner'}
}
function Check-Installed([string]$Phase){
    foreach($name in @('sidecar-fixture.apk','sidecar-fixture-tests.apk')){
        $item=$artifacts[$name];$paths=(Adb "$Phase-path-$name" @('shell','pm','path',$item.package)).output
        $entries=@($paths -split '\r?\n'|Where-Object {$_ -like 'package:*'})
        if($entries.Count -ne 1){throw 'Expected exactly one base APK; split installations are unsupported'}
        $remote=$entries[0].Substring(8).Trim()
        if($remote -notmatch '^/data/app/[A-Za-z0-9_./=+~-]+\.apk$'){throw 'Unexpected installed APK path'}
        $local=Join-Path $run "device\$Phase-$name"
        Adb "$Phase-pull-$name" @('pull',$remote,$local)|Out-Null
        $hash=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash
        $receipt["${Phase}_$name"]=$hash
        if($hash -ne $item.sha256){throw "Installed APK mismatch: $name"}
    }
}
function Read-Marker([string]$Label){
    $raw=(Adb $Label @('shell','run-as',$target,'cat','shared_prefs/control-ui-revocation.xml')).output
    [xml]$xml=$raw;$result=@{}
    foreach($child in $xml.map.ChildNodes){$result[[string]$child.name]= $(if($child.LocalName -eq 'string'){$child.InnerText}else{$child.GetAttribute('value')})}
    return $result
}
function Check-Frozen{
    if((Get-FileHash -LiteralPath $inputManifest).Hash -ne $inputManifestHash -or (Get-FileHash -LiteralPath $artifactManifest).Hash -ne $artifactManifestHash){throw 'Candidate manifests drifted during execution'}
    foreach($row in $sourceRows){if((Get-FileHash -LiteralPath $row.absolute).Hash -ne $row.sha256){throw "Source drift: $($row.path)"}}
    foreach($item in $artifacts.Values){if((Get-FileHash -LiteralPath $item.path).Hash -ne $item.sha256){throw "APK drift: $($item.file)"}}
}
try{
    Copy-Item -LiteralPath $inputManifest -Destination (Join-Path $run 'inputs\inputs.json')
    Copy-Item -LiteralPath $artifactManifest -Destination (Join-Path $run 'inputs\artifacts.json')
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $run 'inputs\run-control-tests.ps1')
    Write-Json (Join-Path $run 'inputs\verified-sources.json') $sourceRows
    foreach($item in $artifacts.Values){
        $started=Utc;$badging=& $aapt dump badging $item.path 2>&1|Out-String
        $code=$LASTEXITCODE;$badgingPath=Join-Path $run ('inputs\'+$item.file+'.badging.log');[IO.File]::WriteAllText($badgingPath,$badging,$utf8)
        $receipt.commands+=,@{label='aapt-package-'+$item.file;arguments=@('dump','badging',$item.path);started_utc=$started;ended_utc=(Utc);exit_code=$code;log=[IO.Path]::GetRelativePath($run,$badgingPath);log_sha256=(Get-FileHash -LiteralPath $badgingPath).Hash}
        if($code -ne 0 -or $badging -notmatch ("(?m)^package: name='"+[regex]::Escape($item.package)+"'")){throw "APK does not contain its exact expected package: $($item.file)"}
        $copy=Join-Path $run ('inputs\'+$item.file);Copy-Item -LiteralPath $item.path -Destination $copy
        if((Get-FileHash -LiteralPath $copy).Hash -ne $item.sha256){throw 'APK changed while snapshotting'}
    }
    $hardware=(Adb 'hardware' @('shell','getprop','ro.hardware')).output.Trim()
    $avd=((Adb 'avd-name' @('emu','avd','name')).output -split '\r?\n')[0].Trim()
    if($hardware -ne 'ranchu' -or $avd -ne $AvdName){throw 'Unexpected emulator hardware or AVD identity'}
    $boot=(Adb 'initial-boot' @('shell','cat','/proc/sys/kernel/random/boot_id')).output.Trim()
    if($boot -notmatch '^[0-9a-f-]{36}$'){throw 'Boot identity unavailable'}
    $receipt.boot_id=$boot;$identityVerified=$true
    $receipt.user_id=(Adb 'current-user' @('shell','am','get-current-user')).output.Trim()
    if($receipt.user_id -ne '0'){throw 'The disposable fixture runner requires primary user 0'}
    $receipt.api_level=(Adb 'api-level' @('shell','getprop','ro.build.version.sdk')).output.Trim()
    if($receipt.api_level -ne '35'){throw 'This runner requires the audited API 35 disposable emulator'}
    if((Adb 'trust' @('shell','dumpsys','trust')).output -notmatch 'deviceLocked=0'){throw 'Unlock the disposable emulator using its existing credential before running'}
    No-Service 'preinstall-services'
    foreach($name in @('sidecar-fixture.apk','sidecar-fixture-tests.apk')){
        $out=(Adb "install-$name" @('install','-r',(Join-Path $run "inputs\$name")) 90).output
        if($out -notmatch '(?m)^Success\s*$'){throw "Install did not report Success: $name"}
    }
    $installed=$true;Check-Installed 'before';No-Service 'initial-services'
    $receipt.initial_permissions=Permission-State 'initial-permissions'
    $registration=(Adb 'instrumentation-registration' @('shell','pm','list','instrumentation',$target)).output
    if($registration -notmatch [regex]::Escape($component)){throw 'Frozen ControlUiInstrumentation is not registered'}
    $auth=(Adb 'credential-verification' @('shell','locksettings','verify','--old',$TestPin) -Secret).output
    if($auth -notmatch 'verified'){throw 'Synthetic credential verification failed; no credential was changed'}
    if($Mode -in @('Combined','All')){
        if($receipt.initial_permissions.RECORD_AUDIO -or $receipt.initial_permissions.POST_NOTIFICATIONS){throw 'Combined requires both permissions initially denied on this disposable fixture; no automatic reset is performed'}
        $out=(Adb 'combined' @('shell','am','instrument','-w','-r','-e','phase','combined','-e','run_id',$runId,$component) 240).output
        $null=Require-Environment $out 'combined'
        if($out -notmatch 'RESULT \d+ checks, 0 failures; synthetic only' -or $out -notmatch 'INSTRUMENTATION_CODE: -1' -or $out -match '(?m)(?:^|stream=)(FAILED|FAIL\s)'){throw 'Combined control checks failed or have no successful terminal result'}
        $receipt.combined_verified=$true;No-Service 'after-combined-services'
    }
    if($Mode -in @('Revocation','All')){
        No-Service 'pre-prepare-services'
        $prepare=Start-Adb 'revocation-prepare' @('shell','am','instrument','-w','-r','-e','phase','revocation_prepare','-e','run_id',$runId,$component)
        $deadline=[DateTime]::UtcNow.AddSeconds(120);$ready=$null
        while([DateTime]::UtcNow -lt $deadline){
            $out=Read-Adb $prepare
            if($out -match '(?m)^INSTRUMENTATION_STATUS: stream=REVOCATION_READY \{[^\r\n]+\}\r?$'){$ready=Event-Json $out 'REVOCATION_READY';break}
            if($prepare.process.HasExited){throw 'Prepare exited before READY; no permission revocation attempted'}
            Start-Sleep -Milliseconds 100
        }
        if($null -eq $ready){throw 'Prepare READY deadline exceeded; no permission revocation attempted'}
        $prepareEnvironment=Require-Environment $out 'revocation_prepare'
        if($ready.run_id -ne $runId -or $ready.boot_id -ne $boot -or $ready.meeting -notmatch '^[0-9a-f-]{36}$' -or [long]$ready.pid -le 0 -or [long]$ready.process_start_elapsed_ms -le 0 -or [long]$ready.ready_elapsed_ms -lt [long]$ready.process_start_elapsed_ms -or $ready.process_token -notmatch '^[0-9a-f-]{36}$' -or [int]$ready.source_starts -ne 1 -or [long]$ready.sample_calls -lt 55 -or [long]$ready.committed_samples -le 0 -or $ready.verified -ne $false -or !$ready.microphone_granted -or !$ready.notifications_granted -or !$ready.notifications_enabled -or [int]$ready.channel_importance -lt 2){throw 'READY does not prove an admitted, visible synthetic source in the current boot/run'}
        foreach($key in @('run_id','phase','pid','boot_id','process_start_elapsed_ms','process_token')){if([string]$ready.$key -ne [string]$prepareEnvironment.$key){throw "READY changed its preparation process identity: $key"}}
        Write-Json (Join-Path $run 'revocation-ready.json') $ready
        $marker=Read-Marker 'prepare-marker'
        foreach($pair in @(@('run_id','run_id'),@('meeting','meeting'),@('prepare_pid','pid'),@('prepare_boot_id','boot_id'),@('prepare_process_start_elapsed_ms','process_start_elapsed_ms'),@('prepare_process_token','process_token'),@('prepare_ready_elapsed_ms','ready_elapsed_ms'),@('source_starts','source_starts'),@('sample_calls','sample_calls'),@('committed_samples','committed_samples'),@('microphone_granted','microphone_granted'),@('notifications_granted','notifications_granted'),@('verified','verified'))){
            if([string]$marker[$pair[0]] -ne [string]$ready.($pair[1])){throw "Durable READY marker mismatch: $($pair[0])"}
        }
        $preparedPid=(Adb 'prepare-pid' @('shell','pidof',$target)).output.Trim()
        if($preparedPid -ne [string]$ready.pid -or $prepare.process.HasExited){throw 'Prepared fixture process is not the uniquely running target'}
        $beforeRevoke=Permission-State 'before-revoke-permissions'
        if(!$beforeRevoke.RECORD_AUDIO){throw 'Microphone was denied before the intended revoke'}
        if((Adb 'before-revoke-boot' @('shell','cat','/proc/sys/kernel/random/boot_id')).output.Trim() -ne $boot){throw 'Boot changed before revoke'}
        $receipt.revocation_issued=$true;$receipt.terminal='revocation-issued'
        # The only permission mutation in this script; never retry a failed or uncertain revoke.
        Adb 'os-revoke-record-audio' @('shell','pm','revoke',$target,'android.permission.RECORD_AUDIO')|Out-Null
        $afterRevoke=Permission-State 'after-revoke-permissions'
        if($afterRevoke.RECORD_AUDIO){throw 'OS permission remains granted after revoke'}
        $deadline=[DateTime]::UtcNow.AddSeconds(30);$gone=$false
        while([DateTime]::UtcNow -lt $deadline){
            $pidResult=Adb 'old-process-exit' @('shell','pidof',$target) -AllowFailure
            if($pidResult.exit_code -notin @(0,1)){throw 'Cannot establish fixture process exit'}
            if($pidResult.exit_code -eq 1 -and [string]::IsNullOrWhiteSpace($pidResult.output)){$gone=$true;break}
            Start-Sleep -Milliseconds 200
        }
        if(!$gone){throw 'Old fixture process has not exited; no force-stop fallback is permitted'}
        $receipt.old_process_exit_observed_utc=Utc
        $null=Complete-Adb $prepare 30 -AllowFailure
        if((Adb 'before-verify-boot' @('shell','cat','/proc/sys/kernel/random/boot_id')).output.Trim() -ne $boot){throw 'Boot changed during permission revocation'}
        No-Service 'before-verify-services'
        $out=(Adb 'revocation-verify' @('shell','am','instrument','-w','-r','-e','phase','revocation_verify','-e','run_id',$runId,$component) 120).output
        $verifyEnvironment=Require-Environment $out 'revocation_verify'
        $verified=Event-Json $out 'REVOCATION_VERIFIED'
        if($verified.run_id -ne $runId -or $verified.meeting -ne $ready.meeting -or $verified.boot_id -ne $boot -or [long]$verified.process_start_elapsed_ms -le [long]$ready.ready_elapsed_ms -or $verified.process_token -eq $ready.process_token -or $verified.microphone_granted -or $verifyEnvironment.microphone_granted -or $verified.verified -ne $true -or [long]$verified.committed_samples -lt [long]$ready.committed_samples){throw 'Verify does not prove a fresh process under the denied permission in the original boot/run'}
        foreach($key in @('run_id','phase','pid','boot_id','process_start_elapsed_ms','process_token')){if([string]$verified.$key -ne [string]$verifyEnvironment.$key){throw "Verified event changed its fresh process identity: $key"}}
        foreach($pair in @(@('prepare_pid','pid'),@('prepare_boot_id','boot_id'),@('prepare_process_start_elapsed_ms','process_start_elapsed_ms'),@('prepare_process_token','process_token'),@('prepare_ready_elapsed_ms','ready_elapsed_ms'))){if([string]$verified.($pair[0]) -ne [string]$ready.($pair[1])){throw "Verified event changed its prepared process identity: $($pair[0])"}}
        if($out -notmatch 'RESULT \d+ actual-permission-revocation recovery checks passed; synthetic input only' -or $out -notmatch 'INSTRUMENTATION_CODE: -1' -or $out -match '(?m)(?:^|stream=)(FAILED|FAIL\s)'){throw 'Fresh-process revocation verification failed or missing'}
        Write-Json (Join-Path $run 'revocation-verified.json') $verified
        $finalMarker=Read-Marker 'verified-marker'
        if($finalMarker['verified'] -ne 'true' -or $finalMarker['run_id'] -ne $runId -or $finalMarker['meeting'] -ne $ready.meeting){throw 'Successful verify marker is not durable'}
        foreach($pair in @(@('verify_pid','pid'),@('verify_boot_id','boot_id'),@('verify_process_start_elapsed_ms','process_start_elapsed_ms'),@('verify_process_token','process_token'))){if([string]$finalMarker[$pair[0]] -ne [string]$verified.($pair[1])){throw "Durable verification identity mismatch: $($pair[0])"}}
        $receipt.revocation_verified=$true
        if((Permission-State 'final-permissions').RECORD_AUDIO){throw 'Microphone was unexpectedly granted after verification'}
        No-Service 'final-services'
    }
    $receipt.terminal='runner-complete'
}catch{$receipt.failure=$_.Exception.Message;$receipt.terminal='failed'}finally{
    foreach($job in $jobs){
        if(!$job.finished){
            try{
                if(!$job.process.HasExited){$job.process.Kill();$job.record.host_adb_terminated=$true;$receipt.postflight_errors+='Host adb terminated; device runner may still require owner inspection'}
                $null=Complete-Adb $job 5 -AllowFailure
            }catch{$receipt.postflight_errors+=$_.Exception.Message}
        }
    }
    if($identityVerified -and $installed){try{Check-Installed 'after'}catch{$receipt.postflight_errors+=$_.Exception.Message}}
    try{Check-Frozen}catch{$receipt.postflight_errors+=$_.Exception.Message}
    $eligible=($null -eq $receipt.failure -and $receipt.postflight_errors.Count -eq 0 -and ($Mode -eq 'Revocation' -or $receipt.combined_verified) -and ($Mode -eq 'Combined' -or $receipt.revocation_verified))
    $receipt.complete=$false;$receipt.passed=$false;$receipt.terminal='evidence-finalizing';$receipt.ended_utc=Utc
    $receiptFile=Join-Path $run 'run-receipt.json'
    # Write an incomplete receipt first. The final receipt binds the file index; the index excludes
    # the receipt itself to avoid a circular hash. No success is written before the index is durable.
    Write-Json $receiptFile $receipt
    try{
        Get-ChildItem -LiteralPath $run -File -Recurse|Where-Object {$_.Name -notin @('evidence-hashes.csv','run-receipt.json')}|ForEach-Object {
            [PSCustomObject]@{path=[IO.Path]::GetRelativePath($run,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
        }|Export-Csv -LiteralPath (Join-Path $run 'evidence-hashes.csv') -NoTypeInformation -Encoding utf8
        $receipt.evidence_manifest_sha256=(Get-FileHash -LiteralPath (Join-Path $run 'evidence-hashes.csv') -Algorithm SHA256).Hash
        $receipt.complete=$eligible;$receipt.passed=$eligible;$receipt.terminal=$(if($eligible){'passed'}else{'failed-or-incomplete'})
        Write-Json $receiptFile $receipt
    }catch{
        $receipt.complete=$false;$receipt.passed=$false;$receipt.terminal='evidence-incomplete';$receipt.postflight_errors+=$_.Exception.Message
        Write-Json $receiptFile $receipt
    }
    Write-Output "Evidence: $run; passed=$($receipt.passed); terminal=$($receipt.terminal)"
}
if(!$receipt.passed){throw "Control acceptance incomplete: $($receipt.failure); $($receipt.postflight_errors -join '; ')"}
