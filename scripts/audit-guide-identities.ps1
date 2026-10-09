param(
    [string]$Adb = (Join-Path $PSScriptRoot '../../tools/platform-tools/adb.exe'),
    [string]$Device = '192.168.1.186:5555',
    [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
function Read-Page([string]$Token, [string]$Kind, [int]$Offset) {
    $arguments = @('-s', $Device, 'shell', 'am', 'broadcast', '-a', 'app.opentv.GUIDE_IDENTITY_AUDIT',
        '-n', 'app.opentv/.data.repo.GuideAuditReceiver', '--receiver-foreground',
        '--es', 'kind', $Kind, '--ei', 'offset', "$Offset", '--ei', 'limit', '100')
    if ($Token) { $arguments += @('--es', 'snapshot', $Token) }
    $raw = (& $Adb @arguments 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'ADB diagnostic failed (raw output intentionally withheld).' }
    $match = [regex]::Match($raw, 'data="(.*)"', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) { throw 'Diagnostic returned no JSON data.' }
    $reply = $match.Groups[1].Value.Trim() | ConvertFrom-Json
    if ($reply.error) { throw "Diagnostic: $($reply.error)" }
    if (-not $reply.snapshot -or -not $reply.page -or $reply.page.kind -ne $Kind -or
        $reply.page.offset -ne $Offset -or ($Token -and $reply.snapshot -ne $Token)) {
        throw 'Invalid or inconsistent diagnostic reply.'
    }
    return $reply
}
$token = ''
$result = [ordered]@{ capturedUtc = [DateTime]::UtcNow.ToString('o') }
foreach ($kind in @('channels', 'aliases')) {
    $first = Read-Page $token $kind 0
    if (-not $token) { $token = $first.snapshot }
    $total = $first.page.total
    $rows = [System.Collections.Generic.List[object]]::new()
    if ($first.page.$kind) { $rows.AddRange([object[]]$first.page.$kind) }
    while ($rows.Count -lt $total) {
        $next = Read-Page $token $kind $rows.Count
        if ($next.snapshot -ne $token -or $next.page.total -ne $total -or
            $next.page.offset -ne $rows.Count -or $next.page.$kind.Count -eq 0) { throw 'Inconsistent snapshot.' }
        $rows.AddRange([object[]]$next.page.$kind)
    }
    $unique = if ($kind -eq 'channels') {
        @($rows.channelId | Sort-Object -Unique).Count
    } else {
        @($rows | ForEach-Object { "$($_.feedId):$($_.guideId)" } | Sort-Object -Unique).Count
    }
    if ($rows.Count -ne $total -or $unique -ne $total) { throw 'Duplicate or incomplete identity audit.' }
    $result[$kind] = $rows.ToArray()
    "Read $total $kind from one immutable identity snapshot."
}
$parent = Split-Path -Parent $OutputPath
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
[System.IO.File]::WriteAllText($OutputPath, ($result | ConvertTo-Json -Depth 10))
