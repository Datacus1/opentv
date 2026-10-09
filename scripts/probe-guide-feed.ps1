param(
    [string]$Adb = (Join-Path $PSScriptRoot '../../tools/platform-tools/adb.exe'),
    [string]$Device = '192.168.1.186:5555',
    [Parameter(Mandatory)][long]$FeedId,
    [Parameter(Mandatory)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
if ($FeedId -le 0) { throw 'Positive feed ID required.' }
$outputFile = [IO.Path]::GetFullPath($OutputPath, (Get-Location).ProviderPath)
if (Test-Path -LiteralPath $outputFile) { throw 'Existing reports will not be overwritten.' }
function Invoke-FeedProbe([string]$Operation, [string]$Token, [int]$Offset = 0) {
    $arguments = @('-s', $Device, 'shell', 'am', 'broadcast', '-a', 'app.opentv.GUIDE_FEED_PROBE',
        '-n', 'app.opentv/.data.repo.GuideFeedProbeReceiver', '--receiver-foreground', '--es', 'operation', $Operation,
        '--ei', 'offset', "$Offset", '--ei', 'limit', '100')
    if ($Operation -eq 'start') { $arguments += @('--el', 'feed', "$FeedId") }
    if ($Token) { $arguments += @('--es', 'token', $Token) }
    $raw = (& $Adb @arguments 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'ADB probe failed; raw output withheld.' }
    $match = [regex]::Match($raw, 'data="(.*)"', [Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) { throw 'No probe JSON returned.' }
    $match.Groups[1].Value.Trim() | ConvertFrom-Json
}
$reply = Invoke-FeedProbe 'start' ''
if ($reply.status -ne 'RUNNING' -or -not $reply.token) { throw "Probe did not start: $($reply.status)" }
$token = $reply.token
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    do {
        Start-Sleep -Milliseconds 1500
        $reply = Invoke-FeedProbe 'poll' $token
        if ($reply.token -ne $token) { throw 'Probe token lost/expired; no absence conclusion.' }
    } while ($reply.status -eq 'RUNNING' -and $timer.Elapsed.TotalSeconds -lt 58)
    if ($reply.status -ne 'FINISHED' -or -not $reply.evidence) { throw "Incomplete probe: $($reply.status)" }
    $first = $reply
    $rows = [Collections.Generic.List[object]]::new()
    $rows.AddRange([object[]]$reply.evidence.report.rows)
    while ($rows.Count -lt $first.total) {
        $reply = Invoke-FeedProbe 'poll' $token $rows.Count
        if ($reply.token -ne $token -or $reply.status -ne 'FINISHED' -or $reply.total -ne $first.total -or
            $reply.offset -ne $rows.Count -or $reply.evidence.report.streamFingerprint -ne $first.evidence.report.streamFingerprint -or
            $reply.evidence.capturedMillis -ne $first.evidence.capturedMillis -or !$reply.evidence.report.rows.Count) {
            throw 'Inconsistent/expired diagnostic page.'
        }
        $rows.AddRange([object[]]$reply.evidence.report.rows)
    }
    if ($rows.Count -ne $first.total -or @($rows.fingerprint | Sort-Object -Unique).Count -ne $first.total) {
        throw 'Duplicate or incomplete identity pages.'
    }
    $first.evidence.report.rows = $rows.ToArray()
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputFile)) | Out-Null
    $bytes = [Text.Encoding]::UTF8.GetBytes(($first.evidence | ConvertTo-Json -Depth 12))
    $file = [IO.File]::Open($outputFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $file.Write($bytes, 0, $bytes.Length) } finally { $file.Dispose() }
    [pscustomobject]@{completion=$first.evidence.report.completion; identities=$rows.Count;
        programmes=$first.evidence.report.encountered; bytes=$first.evidence.report.bytesRead;
        selectorTruncated=$first.evidence.report.selectorTruncated; transportLimit=$first.evidence.transportLimitReached}
} catch {
    try { Invoke-FeedProbe 'cancel' $token | Out-Null } catch { }
    throw
}
