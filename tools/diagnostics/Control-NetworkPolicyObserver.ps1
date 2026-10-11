[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Session,
    [Parameter(Mandatory)][ValidateSet('Pause','Resume','Stop')][string]$Action
)
$ErrorActionPreference = 'Stop'
$resolvedSession = (Resolve-Path -LiteralPath $Session).Path
if (-not (Test-Path -LiteralPath (Join-Path $resolvedSession 'owner.json'))) { throw 'Not an observer session.' }
switch ($Action) {
    'Resume' {
        if ((Get-Content -LiteralPath (Join-Path $resolvedSession 'timeline.jsonl') -Tail 4) -match '"event":"SESSION_END"') { throw 'Ended sessions cannot resume. Start a new observer session.' }
        if (Test-Path -LiteralPath (Join-Path $resolvedSession 'STOP')) { throw 'Stopped sessions cannot resume.' }
        if (Test-Path -LiteralPath (Join-Path $resolvedSession 'PAUSE')) { Remove-Item -LiteralPath (Join-Path $resolvedSession 'PAUSE') }
    }
    default {
        $file = if ($Action -eq 'Pause') { 'PAUSE' } else { 'STOP' }
        $requestedUtc = [DateTimeOffset]::UtcNow
        [IO.File]::WriteAllText((Join-Path $resolvedSession $file), $requestedUtc.ToString('o'))
        # Wait for child logcat to be stopped before giving the device to a human.
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        do {
            Start-Sleep -Milliseconds 200
            $events = Get-Content -LiteralPath (Join-Path $resolvedSession 'timeline.jsonl') -Tail 8
            foreach ($line in $events) {
                try { $entry = $line | ConvertFrom-Json } catch { continue }
                if ($entry.event -eq 'SESSION_END' -or ($Action -eq 'Pause' -and $entry.event -eq 'PAUSED' -and [DateTimeOffset]::Parse($entry.pcUtc) -ge $requestedUtc)) {
                    Write-Host "$Action acknowledged; ADB collector stopped."; return
                }
            }
        } while ([DateTime]::UtcNow -lt $deadline)
        throw 'Control not acknowledged. Do not hand off the device until the collector has stopped.'
    }
}
