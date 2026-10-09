param(
    [string]$Adb = (Join-Path $PSScriptRoot '../../tools/platform-tools/adb.exe'),
    [string]$Device = '192.168.1.186:5555',
    [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
function Read-Page([string]$Token, [int]$Offset) {
    $arguments = @('-s', $Device, 'shell', 'am', 'broadcast', '-a', 'app.opentv.GUIDE_AUDIT',
        '-n', 'app.opentv/.data.repo.GuideAuditReceiver', '--receiver-foreground',
        '--ei', 'offset', "$Offset", '--ei', 'limit', '100')
    if ($Token) { $arguments += @('--es', 'snapshot', $Token) }
    $raw = (& $Adb @arguments 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'ADB diagnostic failed (raw output intentionally withheld).' }
    $match = [regex]::Match($raw, 'data="(.*)"', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) { throw 'Diagnostic returned no JSON data.' }
    $reply = $match.Groups[1].Value.Trim() | ConvertFrom-Json
    if ($reply.error) { throw "Diagnostic: $($reply.error)" }
    if (-not $reply.snapshot -or -not $reply.page) { throw 'Invalid diagnostic reply.' }
    return $reply
}
$first = Read-Page '' 0
$token = $first.snapshot
$total = $first.page.total
$rows = [System.Collections.Generic.List[object]]::new()
$rows.AddRange([object[]]$first.page.rows)
while ($rows.Count -lt $total) {
    $next = Read-Page $token $rows.Count
    if ($next.snapshot -ne $token -or $next.page.total -ne $total -or
        $next.page.offset -ne $rows.Count -or $next.page.rows.Count -eq 0) { throw 'Inconsistent snapshot.' }
    $rows.AddRange([object[]]$next.page.rows)
}
if (($rows.channelId | Sort-Object -Unique).Count -ne $total) { throw 'Duplicate or incomplete audit.' }
# The protected receiver only returns allowlisted names/guide metadata. Never export Channel,
# Source, provider endpoints, playlist bodies, credentials or programme descriptions.
$result = [ordered]@{ capturedUtc = [DateTime]::UtcNow.ToString('o'); total = $total;
    counts = $first.page.counts; rows = $rows.ToArray() }
$parent = Split-Path -Parent $OutputPath
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
[System.IO.File]::WriteAllText($OutputPath, ($result | ConvertTo-Json -Depth 10))
$result.counts | ConvertTo-Json -Compress
"Verified $total distinct variants; safe metadata saved."
