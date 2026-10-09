param(
    [Parameter(Mandatory)][string]$XmlPath,
    [Parameter(Mandatory)][string]$OutputPath,
    [Parameter(Mandatory)][DateTimeOffset]$GuideTime,
    [long]$FeedId = 6,
    [ValidateRange(1,20)][int]$EnabledFeedCount = 3,
    [string[]]$GuideIds = @('CBSWIAT.us', 'CWWTTO.us'),
    [string]$JavaHome = (Join-Path $PSScriptRoot '../../tools/shield-build/jdk-extract/jdk-17.0.20+8'),
    [string]$RobolectricDependencyDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository/org/robolectric/android-all-instrumented/14-robolectric-10818077-i6'),
    [string]$BuildOutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'opentv-offline-guide-diagnostic')
)
$ErrorActionPreference = 'Stop'
# This script has no ADB/HTTP/provider/TV path. Gradle and Robolectric dependencies must already be cached.
function Assert-LocalPath([string]$Path) {
    if ($Path.StartsWith('\\') -or ([IO.DriveInfo]::new([IO.Path]::GetPathRoot($Path))).DriveType -eq 'Network') {
        throw 'Network paths/drives are not supported by this offline diagnostic.'
    }
}
# Validate drive/UNC before Resolve-Path/Get-Item can access an input path.
$inputPath = [IO.Path]::GetFullPath($XmlPath, (Get-Location).ProviderPath)
Assert-LocalPath $inputPath
$outputFile = [IO.Path]::GetFullPath($OutputPath, (Get-Location).ProviderPath)
Assert-LocalPath $outputFile
Assert-LocalPath ([IO.Path]::GetFullPath($BuildOutputDirectory))
Assert-LocalPath ([IO.Path]::GetFullPath($JavaHome))
Assert-LocalPath ([IO.Path]::GetFullPath($RobolectricDependencyDirectory))
# Trusted hydrated local files only; this wrapper is not an adversarial filesystem sandbox.
# OneDrive cloud reparse flags alone are normal here. Actual links/junctions and offline files are not.
$inputFile = Get-Item -LiteralPath $inputPath
Assert-LocalPath $inputFile.FullName
if ($inputFile.PSIsContainer -or $inputFile.FullName.StartsWith('\\') -or
    $inputFile.LinkType -or ($inputFile.Attributes -band [IO.FileAttributes]::Offline)) {
    throw 'Supply an ordinary local XMLTV file, not a directory, link or network path.'
}
if ($outputFile.StartsWith('\\') -or $outputFile -eq $inputFile.FullName -or (Test-Path -LiteralPath $outputFile)) {
    throw 'Choose a new local report path; existing files will not be overwritten.'
}
if ($GuideIds | Where-Object { $_.Contains(',') }) { throw 'Comma-bearing IDs are unsupported by this CLI.' }
$androidJar = Join-Path $RobolectricDependencyDirectory 'android-all-instrumented-14-robolectric-10818077-i6.jar'
if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) {
    throw 'The local Android SDK34 test jar must already be cached; downloading is disabled.'
}
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputFile)) | Out-Null
$variables = @{
    JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    OPENTV_OFFLINE_BUILD_DIR = [IO.Path]::GetFullPath($BuildOutputDirectory)
    OPENTV_ROBOLECTRIC_DEPENDENCY_DIR = [IO.Path]::GetFullPath($RobolectricDependencyDirectory)
    OPENTV_DIAGNOSTIC_XML_FILE = $inputFile.FullName
    OPENTV_DIAGNOSTIC_REPORT_FILE = $outputFile
    OPENTV_DIAGNOSTIC_NOW_MILLIS = "$($GuideTime.ToUnixTimeMilliseconds())"
    OPENTV_DIAGNOSTIC_FEED_ID = "$FeedId"
    OPENTV_DIAGNOSTIC_ENABLED_FEEDS = "$EnabledFeedCount"
    OPENTV_DIAGNOSTIC_GUIDE_IDS = ($GuideIds -join ',')
}
$saved = @{}
foreach ($key in $variables.Keys) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$savedPath = $env:PATH
try {
    foreach ($key in $variables.Keys) { [Environment]::SetEnvironmentVariable($key, $variables[$key], 'Process') }
    $env:PATH = "$($variables.JAVA_HOME)\bin;$savedPath"
    Push-Location (Join-Path $PSScriptRoot '..')
    try {
        # Re-run deliberately: local input/env changes are not Gradle task cache keys.
        & '.\gradlew.bat' --offline --no-configuration-cache --no-watch-fs --init-script scripts/offline-output.init.gradle testDebugUnitTest --tests '*GuideFeedDiagnosticTest.offline supplied file opt in never fetches a provider' --rerun-tasks
        if ($LASTEXITCODE -ne 0) { throw 'Offline diagnostic test runner failed; no successful scan is claimed.' }
    } finally { Pop-Location }
    if (-not (Test-Path -LiteralPath $outputFile -PathType Leaf)) { throw 'No diagnostic report was produced.' }
    $report = Get-Content -LiteralPath $outputFile -Raw | ConvertFrom-Json
    [pscustomobject]@{ completion = $report.completion; programmes = $report.encountered
        identities = $report.rows.Count; selectorTruncated = [bool]$report.selectorTruncated
        report = $outputFile }
} finally {
    foreach ($key in $variables.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    $env:PATH = $savedPath
}
