param(
    [string]$Adb = (Join-Path $PSScriptRoot '../../tools/platform-tools/adb.exe'),
    [string]$Device = '192.168.1.186:5555',
    [Parameter(Mandatory)][long[]]$ChannelIds,
    [Parameter(Mandatory)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
if ($ChannelIds.Count -notin 1..4 -or @($ChannelIds | Where-Object { $_ -le 0 }).Count -or
    @($ChannelIds | Sort-Object -Unique).Count -ne $ChannelIds.Count) { throw 'Supply 1-4 distinct positive channel IDs.' }
$outputFile = [IO.Path]::GetFullPath($OutputPath, (Get-Location).ProviderPath)
if (Test-Path -LiteralPath $outputFile) { throw 'Choose a new report path; no overwrite.' }
function Invoke-Probe([string]$Operation, [string]$Token) {
    $arguments = @('-s', $Device, 'shell', 'am', 'broadcast', '-a', 'app.opentv.GUIDE_SHORT_PROBE',
        '-n', 'app.opentv/.data.repo.GuideShortProbeReceiver', '--receiver-foreground', '--es', 'operation', $Operation)
    if ($Operation -eq 'start') { $arguments += @('--es', 'channels', ($ChannelIds -join ',')) }
    if ($Token) { $arguments += @('--es', 'token', $Token) }
    $raw = (& $Adb @arguments 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'ADB probe failed; raw output withheld.' }
    $match = [regex]::Match($raw, 'data="(.*)"', [Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) { throw 'No probe JSON returned.' }
    $match.Groups[1].Value.Trim() | ConvertFrom-Json
}
$reply = Invoke-Probe 'start' ''
if ($reply.status -ne 'RUNNING' -or -not $reply.token) { throw "Probe did not start: $($reply.status)" }
$token = $reply.token
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    do {
        Start-Sleep -Milliseconds 1000
        $reply = Invoke-Probe 'poll' $token
        if ($reply.token -ne $token) { throw 'Probe lost/expired; absence not established.' }
    } while ($reply.status -eq 'RUNNING' -and $timer.Elapsed.TotalSeconds -lt 50)
    if ($reply.status -eq 'RUNNING') { $reply = Invoke-Probe 'cancel' $token }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputFile)) | Out-Null
    $bytes = [Text.Encoding]::UTF8.GetBytes(($reply | ConvertTo-Json -Depth 10))
    $file = [IO.File]::Open($outputFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $file.Write($bytes, 0, $bytes.Length) } finally { $file.Dispose() }
    $reply.rows | Select-Object channelId, name, @{n='status';e={if($_.counts.status){$_.counts.status}else{'COMPLETE'}}},
        @{n='encountered';e={[int]$_.counts.encountered}}, @{n='usable';e={[int]$_.counts.usableInWindow}}, @{n='current';e={[int]$_.counts.current}}
    "Orchestration: $($reply.status); per-row statuses are authoritative."
} catch {
    try { Invoke-Probe 'cancel' $token | Out-Null } catch { }
    throw
}
