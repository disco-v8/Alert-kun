[CmdletBinding()]
param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial = '',
    [int]$Port = 5038,
    [ValidateRange(60,3600)][int]$IntervalSeconds = 60,
    [ValidateRange(1,168)][int]$Hours = 24,
    [int]$MaxSamples = 0,
    [string]$OutputRoot = '',
    [switch]$LibraryOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-PolicyValue([string]$Dump, [int]$AppUid) {
    if ($Dump -match '(?im)^\s*(Permission Denial|Failure while dumping|error:)|DUMP TIMEOUT') { return 'UNKNOWN' }
    $match = [regex]::Match($Dump, "(?m)^\s*UID=$AppUid policy=(\d+)\b")
    if ($match.Success) { return $match.Groups[1].Value }
    # A missing policy row means NONE only in a successful, recognizable full dump.
    if ($Dump -match 'UID policies:|mUidPolicy|Restrict background:') { return '0' }
    return 'UNKNOWN'
}

function Get-UidPolicyEvents([string]$Dump, [int]$AppUid) {
    # Preserve the OS timestamp; a return to the old value between polls is still evidence.
    return @($Dump -split '\r?\n' | Where-Object { $_ -match "\b$AppUid\b" -and $_ -match 'policy changed|UID policy|uidPolicy' })
}

function Get-DenylistEvidence([string]$Dump, [string]$PackageName = 'direct.kabu.alertkun') {
    $result = [ordered]@{ manual = 'UNKNOWN'; dynamic = 'UNKNOWN' }
    $section = ''
    foreach ($line in ($Dump -split '\r?\n')) {
        if ($line -match '^\s*ManualDenylist:\s*$') { $section = 'manual'; $result.manual = 'ABSENT'; continue }
        if ($line -match '^\s*DynamicDenylist:\s*$') { $section = 'dynamic'; $result.dynamic = 'ABSENT'; continue }
        if ($section -and $line.Trim() -eq $PackageName) { $result[$section] = 'PRESENT' }
        elseif ($section -and $line.Trim() -and $line -notmatch '^\s+[A-Za-z0-9_.]+\s*$') { $section = '' }
    }
    if ($Dump -match 'Timeout|Failure while dumping|Permission Denial|Exception') {
        $result.manual = 'UNKNOWN'; $result.dynamic = 'UNKNOWN'
    }
    return $result
}

function Quote-Argument([string]$Value) {
    # Windows CreateProcess quoting, including quotes and trailing backslashes.
    return '"' + ([regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1')) + '"'
}

function Invoke-Adb([string[]]$AdbArguments, [int]$TimeoutSeconds = 15) {
    if (Test-Path -LiteralPath "$script:Session\PAUSE") { throw 'Observation paused; no ADB call allowed.' }
    if (Test-Path -LiteralPath "$script:Session\STOP") { throw 'Observation stopped; no ADB call allowed.' }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $AdbPath
    $info.Arguments = (@('-P', "$Port", '-s', $Serial) + $AdbArguments | ForEach-Object { Quote-Argument $_ }) -join ' '
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while (-not $process.WaitForExit(200)) {
            if ([DateTime]::UtcNow -gt $deadline -or (Test-Path -LiteralPath "$script:Session\PAUSE") -or (Test-Path -LiteralPath "$script:Session\STOP")) {
                $process.Kill(); $process.WaitForExit()
                return @{ ok = $false; output = $stdout.Result; error = 'TIMEOUT_OR_CONTROL'; exit = -1 }
            }
        }
        return @{ ok = ($process.ExitCode -eq 0); output = $stdout.Result; error = $stderr.Result; exit = $process.ExitCode }
    } finally { $process.Dispose() }
}

function Save-Text([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path, $Value, (New-Object Text.UTF8Encoding($false)))
}
function Write-Event([string]$Event, $Fields) {
    $entry = [ordered]@{ pcUtc = [DateTimeOffset]::UtcNow.ToString('o'); event = $Event }
    foreach ($key in $Fields.Keys) { $entry[$key] = $Fields[$key] }
    [IO.File]::AppendAllText("$script:Session\timeline.jsonl", (($entry | ConvertTo-Json -Compress -Depth 8) + "`n"), (New-Object Text.UTF8Encoding($false)))
}

function Start-Logcat {
    $script:LogPart++
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $AdbPath
    $tags = @('NetworkPolicy:V','NetworkPolicyManager:V','NetworkPolicyManagerService:V','NetworkPolicyLogger:V',
        'DynamicDenylistManager:V','DataSaverBackend:V','AppDataUsage:V','DeviceIdleController:V',
        'PowerManagerService:V','BatterySaverController:V','BatterySaverPolicy:V','BatteryOptimizeUtils:V',
        'ActivityManager:I','ConnectivityService:I','Settings:I','SettingsDumpService:V',
        'BatteryUtils:V','BatterySettingsStorage:V','BatteryUsageStats:V','AdaptiveBattery:V',
        'screen_toggled:I','power_screen_state:I','device_idle:I','device_idle_light:I',
        'battery_saver_mode:I','am_proc_start:I','am_proc_died:I','*:S')
    # Buffered prefix gives overlap on reconnect/rotation. Never clear logcat or change tag levels.
    $arguments = @('-P', "$Port", '-s', $Serial, 'logcat', '-b', 'main', '-b', 'system', '-b', 'events', '-v', 'epoch', '-T', '2000') + $tags
    $info.Arguments = ($arguments | ForEach-Object { Quote-Argument $_ }) -join ' '
    $script:LogFile = "$script:Session\logcat-{0:D4}.log" -f $script:LogPart
    # Start-Process redirects without retaining an unbounded in-memory queue.
    $stderrFile = "$script:Session\logcat-{0:D4}.stderr" -f $script:LogPart
    $script:Logcat = Start-Process -FilePath $AdbPath -ArgumentList $info.Arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $script:LogFile -RedirectStandardError $stderrFile
    Write-Event 'LOGCAT_START' @{ part = $script:LogPart; pid = $script:Logcat.Id; overlapLines = 2000 }
}
function Stop-Logcat {
    if ($null -ne $script:Logcat) {
        if (-not $script:Logcat.HasExited) { $script:Logcat.Kill(); $script:Logcat.WaitForExit() }
        $script:Logcat.Dispose(); $script:Logcat = $null
        Write-Event 'LOGCAT_STOP' @{}
    }
}

function Save-Details([string]$Directory) {
    foreach ($item in @(
        @{ name='deviceidle'; args=@('shell','dumpsys deviceidle') },
        @{ name='power'; args=@('shell','dumpsys power') },
        @{ name='settings-dump'; args=@('shell','dumpsys activity service com.android.settings/.SettingsDumpService') }
    )) {
        $result = Invoke-Adb $item.args
        Save-Text "$Directory\$($item.name).txt" $result.output
        Write-Event 'DETAIL_RESULT' @{ name=$item.name; ok=$result.ok; error=$result.error }
    }
    # This Pixel's providers may not expose the lists. Never treat absence/timeout as ABSENT.
    $provider = Invoke-Adb @('shell','dumpsys activity provider com.android.settings/com.google.android.settings.fuelgauge.BatteryUsageContentProvider') 8
    $lines = ($provider.output -split '\r?\n' | Where-Object { $_ -match 'Denylist|denylist|direct\.kabu\.alertkun|Failure while dumping|Timeout|Permission Denial|PROVIDER|No providers' }) -join "`n"
    Save-Text "$Directory\denylist-probe.txt" ($lines + "`nexit=" + $provider.exit + "`nerror=" + $provider.error)
    $evidence = Get-DenylistEvidence $provider.output
    if (-not $provider.ok) { $evidence.manual='UNKNOWN'; $evidence.dynamic='UNKNOWN' }
    Write-Event 'DENYLIST_EVIDENCE' $evidence
    Save-Text "$Directory\denylist-evidence.json" ($evidence | ConvertTo-Json)
}

if ($LibraryOnly) { return }
if (-not $OutputRoot) { $OutputRoot = Join-Path $PSScriptRoot '..\..\build\investigation\network-policy' }
$resolvedRoot = [IO.Path]::GetFullPath($OutputRoot)
[void][IO.Directory]::CreateDirectory($resolvedRoot)
$script:Session = Join-Path $resolvedRoot ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '-' + $Serial)
[void][IO.Directory]::CreateDirectory($script:Session)
$script:Logcat = $null; $script:LogPart = 0
$lease = $null
try {
    # One observer per physical device. Never kill another owner's ADB/process.
    $leaseRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\build\investigation\network-policy'))
    [void][IO.Directory]::CreateDirectory($leaseRoot)
    $lease = [IO.File]::Open((Join-Path $leaseRoot "$Serial.lock"), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    Save-Text "$script:Session\owner.json" (@{ pid=$PID; serial=$Serial; port=$Port; intervalSeconds=$IntervalSeconds; hours=$Hours } | ConvertTo-Json)
    Write-Host "Session: $script:Session"
    Write-Event 'SESSION_START' @{ serial=$Serial; intervalSeconds=$IntervalSeconds; hours=$Hours }
    $uidResult = Invoke-Adb @('shell','cmd package list packages -U direct.kabu.alertkun')
    $uidMatch = [regex]::Match($uidResult.output, '(?m)^package:direct\.kabu\.alertkun uid:(\d+)\s*$')
    if (-not $uidResult.ok -or -not $uidMatch.Success) { throw 'Cannot resolve exact AlertKun UID.' }
    $appUid = [int]$uidMatch.Groups[1].Value
    Write-Event 'APP_UID' @{ uid=$appUid }
    $build = Invoke-Adb @('shell','getprop ro.build.fingerprint')
    Save-Text "$script:Session\build-fingerprint.txt" $build.output
    Start-Logcat
    $lastPolicy = 'UNKNOWN'; $lastSample = $null; $lastPolicyReadUtc = $null; $pendingAfter = $null; $lastDetailed = [DateTime]::MinValue
    $seenPolicyEvents = New-Object 'System.Collections.Generic.HashSet[string]'
    $policyHistoryInitialized = $false
    $deadline = [DateTime]::UtcNow.AddHours($Hours); $sampleNumber = 0; $wasPaused = $false
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-Path -LiteralPath "$script:Session\STOP")) {
        if (Test-Path -LiteralPath "$script:Session\PAUSE") {
            if (-not $wasPaused) { Stop-Logcat; Write-Event 'PAUSED' @{}; $wasPaused=$true }
            Start-Sleep -Seconds 1; continue
        }
        if ($wasPaused) { Write-Event 'RESUMED' @{}; $wasPaused=$false; Start-Logcat }
        if ($null -eq $script:Logcat -or $script:Logcat.HasExited -or (Get-Item -LiteralPath $script:LogFile).Length -gt 10MB) {
            Stop-Logcat; Start-Logcat
        }
        $sampleNumber++
        $directory = Join-Path $script:Session ('sample-{0:D6}' -f $sampleNumber)
        [void][IO.Directory]::CreateDirectory($directory)
        $startedUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $clock = Invoke-Adb @('shell','date -Iseconds; cat /proc/uptime')
        Save-Text "$directory\device-clock.txt" $clock.output
        $policyReadBeginUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $policy = Invoke-Adb @('shell','dumpsys netpolicy')
        $policyReadEndUtc = [DateTimeOffset]::UtcNow.ToString('o')
        # Target UID state + global controls + OS event history. Exclude subscriber templates/SSID.
        $uidRows = ($policy.output -split '\r?\n' | Where-Object { $_ -match "\b$appUid\b|Restrict background:|mRestrictBackground|Data saver|data saver" }) -join "`n"
        Save-Text "$directory\netpolicy-uid.txt" $uidRows
        $value = if ($policy.ok) { Get-PolicyValue $policy.output $appUid } else { 'UNKNOWN' }
        $newPolicyEvents = @()
        if ($policy.ok) {
            foreach ($line in (Get-UidPolicyEvents $policy.output $appUid)) {
                if ($seenPolicyEvents.Add($line) -and $policyHistoryInitialized) { $newPolicyEvents += $line }
            }
            $policyHistoryInitialized = $true
        }
        $state = Invoke-Adb @('shell', 'dumpsys power; dumpsys deviceidle; dumpsys connectivity')
        $stateRows = ($state.output -split '\r?\n' | Where-Object { $_ -match 'mWakefulness=|mIsPowered=|mPlugType=|mBatteryLevel=|mIsInteractive=|mLightState=|mState=|mScreenOn=|mCharging=|mForceIdle=|mDeepEnabled=|mLightEnabled=|mIsBatterySaverEnabled=|mFullEnabled=|mAdaptiveEnabled=|Battery Saver is currently:|mSettingBatterySaverEnabled=|mLowPowerStandbyActive=|Active default network:|NetworkAgentInfo\{' })
        # NetworkAgentInfo contains IP/SSID. Store only its network number and first transport/capabilities.
        $safeState = foreach ($line in $stateRows) {
            if ($line -match 'NetworkAgentInfo\{') {
                $net = [regex]::Match($line,'network\{\d+\}').Value
                $transport = [regex]::Match($line,'Transports:\s*([A-Z |]+?)(?:\s+Capabilities:)').Groups[1].Value
                "$net transports=$transport wifi=$($transport -match '\bWIFI\b') cellular=$($transport -match '\bCELLULAR\b')"
            } else { $line }
        }
        Save-Text "$directory\device-state.txt" ($safeState -join "`n")
        $app = Invoke-Adb @('shell','run-as direct.kabu.alertkun tail -n 300 files/observability/monitoring-observation.log')
        Save-Text "$directory\app-observation.txt" $app.output
        Write-Event 'SAMPLE' @{ sample=$sampleNumber; startedUtc=$startedUtc; policyReadBeginUtc=$policyReadBeginUtc; policyReadEndUtc=$policyReadEndUtc; deviceClock=$clock.output.Trim(); clockOk=$clock.ok; uid=$appUid; policy=$value; netpolicyOk=$policy.ok; netpolicyError=$policy.error; stateOk=$state.ok; stateError=$state.error; appLogOk=$app.ok; appLogError=$app.error; logcatPart=$script:LogPart }
        if ($pendingAfter) {
            Save-Text "$pendingAfter\after-sample.txt" $directory
            $pendingAfter=$null
        }
        if (($value -ne 'UNKNOWN' -and $value -ne $lastPolicy) -or $newPolicyEvents.Count -gt 0) {
            $event = if ($lastPolicy -eq 'UNKNOWN') { 'POLICY_BASELINE' } elseif ($value -ne 'UNKNOWN' -and $value -ne $lastPolicy) { 'POLICY_CHANGE' } else { 'POLICY_HISTORY_CHANGE' }
            Write-Event $event @{ previous=$lastPolicy; current=$value; previousSample=$lastSample; currentSample=$directory; uid=$appUid; changeWindowStartUtc=$lastPolicyReadUtc; changeWindowEndUtc=$policyReadEndUtc }
            Save-Text "$directory\change-context.json" (@{ event=$event; previous=$lastPolicy; current=$value; before=$lastSample; observedIn=$directory; changeWindowStartUtc=$lastPolicyReadUtc; changeWindowEndUtc=$policyReadEndUtc; logcatPart=$script:LogPart; newOsPolicyEvents=$newPolicyEvents } | ConvertTo-Json -Depth 4)
            # Details only on a policy change, rate-limited to 5 minutes (baseline included).
            if (([DateTime]::UtcNow - $lastDetailed).TotalMinutes -ge 5) { Save-Details $directory; $lastDetailed=[DateTime]::UtcNow }
            else { Write-Event 'DETAIL_RATE_LIMITED' @{ sample=$sampleNumber } }
            $pendingAfter=$directory
            if ($value -ne 'UNKNOWN') { $lastPolicy=$value }
        }
        # Failed reads never advance the previous known-state sample or narrow the change window.
        if ($value -ne 'UNKNOWN') { $lastSample=$directory; $lastPolicyReadUtc=$policyReadBeginUtc }
        if ($MaxSamples -gt 0 -and $sampleNumber -ge $MaxSamples) { break }
        $size = (Get-ChildItem -LiteralPath $script:Session -Recurse -File | Measure-Object Length -Sum).Sum
        if ($size -gt 256MB) { Write-Event 'STORAGE_LIMIT_STOP' @{ bytes=$size }; break }
        # Low-frequency samples; control files checked each second, including during human waits.
        for ($second=0; $second -lt $IntervalSeconds; $second++) {
            if ((Test-Path -LiteralPath "$script:Session\PAUSE") -or (Test-Path -LiteralPath "$script:Session\STOP")) { break }
            Start-Sleep -Seconds 1
        }
    }
} catch {
    if ($script:Session -and ((Test-Path -LiteralPath "$script:Session\STOP") -or (Test-Path -LiteralPath "$script:Session\PAUSE"))) {
        Write-Event 'CONTROL_INTERRUPT' @{}
    } else {
        if ($script:Session) { Write-Event 'OBSERVER_ERROR' @{ error=$_.Exception.GetType().Name; message=$_.Exception.Message } }
        throw
    }
} finally {
    Stop-Logcat
    if ($script:Session) { Write-Event 'SESSION_END' @{} }
    if ($null -ne $lease) { $lease.Dispose() }
}
