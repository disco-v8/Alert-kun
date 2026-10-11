[CmdletBinding()]
param(
    [ValidateRange(1,168)][int]$Hours = 24,
    [ValidateRange(60,3600)][int]$IntervalSeconds = 60,
    [int]$MaxSamples = 0
)
$ErrorActionPreference = 'Stop'
$outputRoot = [IO.Path]::GetFullPath("$PSScriptRoot\..\..\build\investigation\network-policy")
[void][IO.Directory]::CreateDirectory($outputRoot)
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
$stdoutFile = Join-Path $outputRoot "launcher-$stamp.stdout"
$stderrFile = Join-Path $outputRoot "launcher-$stamp.stderr"
$observerPath = Join-Path $PSScriptRoot 'Observe-NetworkPolicy.ps1'
$arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -OutputRoot "{1}" -Hours {2} -IntervalSeconds {3} -MaxSamples {4}' -f $observerPath,$outputRoot,$Hours,$IntervalSeconds,$MaxSamples
$process = Start-Process -FilePath "$PSHOME\powershell.exe" -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
Write-Output "Observer PID: $($process.Id)"
Write-Output "Session path will be recorded in: $stdoutFile"
Write-Output "Errors: $stderrFile"
$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    Start-Sleep -Milliseconds 100
    $sessionLine = Get-Content -LiteralPath $stdoutFile -ErrorAction SilentlyContinue | Where-Object { $_ -match '^Session: ' } | Select-Object -First 1
    if ($sessionLine) { Write-Output $sessionLine; return }
    $process.Refresh()
    if ($process.HasExited) { throw "Observer exited during startup. Inspect $stderrFile" }
} while ([DateTime]::UtcNow -lt $deadline)
throw "Observer startup not acknowledged. Inspect $stdoutFile and $stderrFile; PID $($process.Id)."
