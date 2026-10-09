param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$')][string]$Version = '1.0.0-rc.2',
    [string[]]$IdeSandboxPath = @(),
    [ValidatePattern('^[A-Z]{2,4}-[0-9]{3}\.[0-9]+(?:\.[0-9]+){0,2}$')]
    [string[]]$ExpectedVerificationIde = @('IC-251.26927.53', 'IU-251.26927.53')
)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
if ($ExpectedVerificationIde.Count -lt 1 -or $ExpectedVerificationIde.Count -gt 16) { throw 'Specify between 1 and 16 exact verifier IDE build IDs.' }
if (@($ExpectedVerificationIde | Select-Object -Unique).Count -ne $ExpectedVerificationIde.Count) { throw 'The verifier IDE matrix contains duplicates.' }
function Read-BoundedStreamText([IO.Stream]$Stream, [long]$MaxBytes) {
    # StreamReader buffering and file growth cannot bypass the character limit.
    $beaconReader = [IO.StreamReader]::new($Stream, [Text.Encoding]::UTF8, $true, 8192, $true)
    $beaconBuilder = [Text.StringBuilder]::new()
    $beaconBuffer = New-Object char[] 8192
    try {
        while (($beaconRead = $beaconReader.Read($beaconBuffer, 0, $beaconBuffer.Length)) -gt 0) {
            if ($beaconBuilder.Length + $beaconRead -gt $MaxBytes) { throw 'Text exceeds the release-check read limit.' }
            [void]$beaconBuilder.Append($beaconBuffer, 0, $beaconRead)
        }
        return $beaconBuilder.ToString()
    } finally { $beaconReader.Dispose() }
}
function Read-BoundedText([string]$Path, [long]$MaxBytes = 1MB) {
    $beaconFile = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        if ($beaconFile.Length -gt $MaxBytes) { throw "File exceeds the release-check read limit: $Path" }
        return Read-BoundedStreamText $beaconFile $MaxBytes
    } finally { $beaconFile.Dispose() }
}
function ConvertTo-SafeXml([string]$Text) {
    $beaconSettings = [Xml.XmlReaderSettings]::new()
    $beaconSettings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $beaconSettings.XmlResolver = $null
    $beaconStringReader = [IO.StringReader]::new($Text)
    $beaconXmlReader = [Xml.XmlReader]::Create($beaconStringReader, $beaconSettings)
    try {
        $beaconDocument = [Xml.XmlDocument]::new()
        $beaconDocument.XmlResolver = $null
        $beaconDocument.Load($beaconXmlReader)
        return ,$beaconDocument
    } finally { $beaconXmlReader.Dispose(); $beaconStringReader.Dispose() }
}
function Get-StreamHash([System.IO.Stream]$Stream) {
    $beaconHasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($beaconHasher.ComputeHash($Stream)).Replace('-', '').ToLowerInvariant() }
    finally { $beaconHasher.Dispose() }
}
$beaconPackage = Get-Item -LiteralPath (Join-Path $beaconRoot "build/distributions/jvm-beacon-$Version.zip")
if ($beaconPackage.Length -gt 64MB) { throw 'Installation package exceeds the 64 MiB release-check limit.' }
$beaconZip = [IO.Compression.ZipFile]::OpenRead($beaconPackage.FullName)
try {
    if ($beaconZip.Entries.Count -gt 1000) { throw 'Installation package contains too many entries.' }
    $beaconJars = @($beaconZip.Entries | Where-Object { $_.FullName.EndsWith('.jar') })
    if ($beaconJars.Count -ne 1 -or $beaconJars[0].FullName -ne "jvm-beacon/lib/jvm-beacon-$Version.jar") { throw 'Unexpected distribution contents: expected only the plugin JAR.' }
    if ($beaconJars[0].Length -gt 64MB) { throw 'Plugin JAR exceeds the 64 MiB release-check limit.' }
    $beaconStream = $beaconJars[0].Open()
    $beaconMemory = New-Object IO.MemoryStream
    try {
        $beaconCopyBuffer = New-Object byte[] 8192
        while (($beaconRead = $beaconStream.Read($beaconCopyBuffer, 0, $beaconCopyBuffer.Length)) -gt 0) {
            if ($beaconMemory.Length + $beaconRead -gt 64MB) { throw 'Expanded plugin JAR exceeds the release-check limit.' }
            $beaconMemory.Write($beaconCopyBuffer, 0, $beaconRead)
        }
    } finally { $beaconStream.Dispose() }
    try {
        $beaconMemory.Position = 0
        $beaconJarHash = Get-StreamHash $beaconMemory
        $beaconMemory.Position = 0
        $beaconJar = [IO.Compression.ZipArchive]::new($beaconMemory, [IO.Compression.ZipArchiveMode]::Read, $true)
        try {
            if ($beaconJar.Entries.Count -gt 10000) { throw 'Plugin JAR contains too many entries.' }
            $beaconDescriptorEntry = $beaconJar.GetEntry('META-INF/plugin.xml')
            if ($null -eq $beaconDescriptorEntry -or $beaconDescriptorEntry.Length -gt 256KB) { throw 'Missing or oversized plugin descriptor.' }
            $beaconDescriptorStream = $beaconDescriptorEntry.Open()
            try { $beaconDescriptor = ConvertTo-SafeXml (Read-BoundedStreamText $beaconDescriptorStream 256KB) } finally { $beaconDescriptorStream.Dispose() }
            if ($beaconDescriptor.'idea-plugin'.id -ne 'dev.jvmbeacon' -or $beaconDescriptor.'idea-plugin'.version -ne $Version) { throw 'Packaged descriptor does not match the requested version/id.' }
            $beaconIdeaVersion = $beaconDescriptor.'idea-plugin'.'idea-version'
            if ($null -eq $beaconIdeaVersion -or -not $beaconIdeaVersion.GetAttribute('since-build')) { throw 'Packaged descriptor has no minimum IDEA build.' }
            $beaconCompatibility = [ordered]@{
                sinceBuild = $beaconIdeaVersion.GetAttribute('since-build')
                untilBuild = if ($beaconIdeaVersion.HasAttribute('until-build')) { $beaconIdeaVersion.GetAttribute('until-build') } else { $null }
                note = 'Descriptor eligibility only; neither an absent upper bound nor this check proves compatibility with untested IDE versions.'
            }
            if (@($beaconJar.Entries | Where-Object { $_.FullName -match '(?i)(fixture|test\.class$|\.(jfr|jvmb|p12|jks|pem|key)$)' }).Count -gt 0) { throw 'Unexpected test or diagnostic material in the plugin JAR.' }
        } finally { $beaconJar.Dispose() }
    } finally { $beaconMemory.Dispose() }
} finally { $beaconZip.Dispose() }

$beaconTotals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
$beaconSourceTests = Join-Path $beaconRoot 'src/test/java'
foreach ($beaconSource in Get-ChildItem -LiteralPath $beaconSourceTests -Filter '*Test.java' -Recurse) {
    $beaconClass = $beaconSource.FullName.Substring($beaconSourceTests.Length + 1).Replace('\', '.') -replace '\.java$', ''
    if (-not (Test-Path -LiteralPath (Join-Path $beaconRoot "build/test-results/test/TEST-$beaconClass.xml"))) { throw "Missing test suite: $beaconClass. Run the full test task, not --tests." }
}
foreach ($beaconFile in Get-ChildItem -LiteralPath (Join-Path $beaconRoot 'build/test-results/test') -Filter 'TEST-*.xml') {
    $beaconTest = ConvertTo-SafeXml (Read-BoundedText $beaconFile.FullName 4MB)
    foreach ($beaconKey in @('tests', 'failures', 'errors', 'skipped')) { $beaconTotals[$beaconKey] += [int]$beaconTest.testsuite.$beaconKey }
}
if ($beaconTotals.tests -lt 1 -or $beaconTotals.failures -ne 0 -or $beaconTotals.errors -ne 0 -or $beaconTotals.skipped -ne 0) { throw 'Missing, failing or skipped test results. Run the full Gradle test task first.' }
$beaconVerdicts = @()
foreach ($beaconIde in $ExpectedVerificationIde) {
    $beaconVerifierPath = Join-Path $beaconRoot "build/reports/pluginVerifier/$beaconIde/plugins/dev.jvmbeacon/$Version"
    $beaconVerdict = (Read-BoundedText (Join-Path $beaconVerifierPath 'verification-verdict.txt')).Trim()
    if ($beaconVerdict -notmatch '^Compatible\.(?:\s|$)') { throw "Verifier did not report Compatible for $beaconIde ($Version)." }
    $beaconProblemsPath = Join-Path $beaconVerifierPath 'compatibility-problems.txt'
    if ((Test-Path -LiteralPath $beaconProblemsPath) -and (Read-BoundedText $beaconProblemsPath 4MB).Trim()) { throw "Verifier reported compatibility problems for $beaconIde ($Version)." }
    $beaconTelemetry = Read-BoundedText (Join-Path $beaconVerifierPath 'telemetry.txt')
    if ($beaconTelemetry -notmatch '(?m)^Plugin ID: dev\.jvmbeacon\r?$' -or
        $beaconTelemetry -notmatch ("(?m)^Plugin Version: " + [regex]::Escape($Version) + '\r?$') -or
        $beaconTelemetry -notmatch ("(?m)^Plugin size \(bytes\): " + $beaconPackage.Length + '\r?$')) { throw "Verifier telemetry does not match the requested package ID/version/size: $beaconIde ($Version)." }
    $beaconVerdicts += @{ ide = $beaconIde; verdict = $beaconVerdict; reportPath = $beaconVerifierPath; compatibilityProblems = 0 }
}
$beaconIdeEvidence = @()
foreach ($beaconSandbox in $IdeSandboxPath) {
    # Close this sandbox first: the running IDE can lock or still be appending idea.log.
    $beaconLoaded = Get-FileHash -LiteralPath (Join-Path $beaconSandbox "plugins/jvm-beacon/lib/jvm-beacon-$Version.jar") -Algorithm SHA256
    if ($beaconLoaded.Hash.ToLowerInvariant() -ne $beaconJarHash) { throw "Sandbox JAR differs from the installation package: $beaconSandbox" }
    $beaconLines = (Read-BoundedText (Join-Path $beaconSandbox 'log/idea.log') 64MB) -split '\r?\n'
    $beaconStart = -1
    for ($beaconIndex = 0; $beaconIndex -lt $beaconLines.Length; $beaconIndex++) { if ($beaconLines[$beaconIndex].Contains('IDE STARTED')) { $beaconStart = $beaconIndex } }
    if ($beaconStart -lt 0) { throw "No IDE session found: $beaconSandbox" }
    $beaconSession = $beaconLines[$beaconStart..($beaconLines.Length - 1)]
    $beaconLoad = @($beaconSession | Where-Object { $_.Contains('Loaded custom plugins:') -and $_.Contains("JVM Beacon ($Version)") })
    $beaconShutdown = @($beaconSession | Where-Object { $_.Contains('IDE SHUTDOWN') })
    $beaconErrors = @($beaconSession | Where-Object { $_ -cmatch '\sERROR\s+-' }).Count
    if ($beaconLoad.Count -eq 0 -or $beaconShutdown.Count -eq 0) { throw "Version not loaded or sandbox not closed: $beaconSandbox" }
    if ($beaconErrors -gt 0) { throw "IDE session contains $beaconErrors ERROR lines; inspect and resolve before acceptance." }
    $beaconIdeEvidence += @{ sandbox = $beaconSandbox; jarSha256 = $beaconJarHash; loaded = $beaconLoad; shutdown = $beaconShutdown; errorLines = $beaconErrors }
}
$beaconResult = [ordered]@{
    version = $Version; checkedAt = (Get-Date).ToString('o')
    packageBytes = $beaconPackage.Length; packageSha256 = (Get-FileHash -LiteralPath $beaconPackage.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    packagedJarSha256 = $beaconJarHash; ideaVersion = $beaconCompatibility; tests = $beaconTotals
    expectedVerificationIde = $ExpectedVerificationIde; verifier = $beaconVerdicts; ideSessions = $beaconIdeEvidence
    note = 'Automated package and log checks only. Manual GUI acceptance and test coverage are recorded separately; this script cannot certify them.'
}
$beaconOutput = Join-Path $beaconRoot "build/reports/release-checks-$Version.json"
$beaconResult | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $beaconOutput -Encoding UTF8
$beaconResult | ConvertTo-Json -Depth 6
Write-Host "RELEASE_PACKAGE_CHECK_PASS: $beaconOutput"
