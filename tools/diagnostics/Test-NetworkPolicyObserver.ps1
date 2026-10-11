$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\Observe-NetworkPolicy.ps1" -LibraryOnly
function Assert-Equal($Expected, $Actual) {
    if ($Expected -ne $Actual) { throw "Expected $Expected; got $Actual" }
}
Assert-Equal '1' (Get-PolicyValue "Restrict background: false`n  UID=10456 policy=1 (REJECT_METERED_BACKGROUND)" 10456)
Assert-Equal '0' (Get-PolicyValue "Restrict background: false`n  UID=1000 policy=4" 10456)
Assert-Equal 'UNKNOWN' (Get-PolicyValue 'error: device offline' 10456)
Assert-Equal 'UNKNOWN' (Get-PolicyValue '' 10456)
Assert-Equal 'UNKNOWN' (Get-PolicyValue "Restrict background: false`nFailure while dumping the service: Timeout" 10456)
$events = @(Get-UidPolicyEvents "2026-10-10T14:29:00 - 10456 policy changed from 0 to 1`n2026-10-10T14:29:01 - 10456 policy changed from 1 to 0`n1000 policy changed from 0 to 1`nUID=10456 policy=0" 10456)
Assert-Equal 2 $events.Count
Assert-Equal '2026-10-10T14:29:00 - 10456 policy changed from 0 to 1' $events[0]
$evidence = Get-DenylistEvidence "Dump of DynamicDenylistManager:`n ManualDenylist:`n  example.other`n DynamicDenylist:`n  direct.kabu.alertkun"
Assert-Equal 'ABSENT' $evidence.manual
Assert-Equal 'PRESENT' $evidence.dynamic
$evidence = Get-DenylistEvidence "ManualDenylist:`n  direct.kabu.alertkun`nDynamicDenylist:`n"
Assert-Equal 'PRESENT' $evidence.manual
Assert-Equal 'ABSENT' $evidence.dynamic
$evidence = Get-DenylistEvidence 'No providers match'
Assert-Equal 'UNKNOWN' $evidence.manual
Assert-Equal 'UNKNOWN' $evidence.dynamic
$evidence = Get-DenylistEvidence "ManualDenylist:`n Failure while dumping the provider: Timeout"
Assert-Equal 'UNKNOWN' $evidence.manual
Assert-Equal 'UNKNOWN' $evidence.dynamic
Assert-Equal '"C:\path with spaces\\"' (Quote-Argument 'C:\path with spaces\')
Write-Host 'PASS: policy parsing, missing/failed dump, transient policy history, manual/dynamic evidence, process quoting.'
