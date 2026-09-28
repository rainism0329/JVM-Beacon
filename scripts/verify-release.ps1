param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$')][string]$Version = '1.0.0-rc.1',
    [string[]]$IdeSandboxPath = @()
)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-StreamHash([System.IO.Stream]$Stream) {
    $beaconHasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($beaconHasher.ComputeHash($Stream)).Replace('-', '').ToLowerInvariant() }
    finally { $beaconHasher.Dispose() }
}
$beaconPackage = Get-Item -LiteralPath (Join-Path $beaconRoot "build/distributions/jvm-beacon-$Version.zip")
$beaconZip = [IO.Compression.ZipFile]::OpenRead($beaconPackage.FullName)
try {
    $beaconJars = @($beaconZip.Entries | Where-Object { $_.FullName.EndsWith('.jar') })
    if ($beaconJars.Count -ne 1 -or $beaconJars[0].FullName -ne "jvm-beacon/lib/jvm-beacon-$Version.jar") { throw 'Unexpected distribution contents: expected only the plugin JAR.' }
    $beaconStream = $beaconJars[0].Open()
    $beaconMemory = New-Object IO.MemoryStream
    try { $beaconStream.CopyTo($beaconMemory) } finally { $beaconStream.Dispose() }
    try {
        $beaconMemory.Position = 0
        $beaconJarHash = Get-StreamHash $beaconMemory
        $beaconMemory.Position = 0
        $beaconJar = [IO.Compression.ZipArchive]::new($beaconMemory, [IO.Compression.ZipArchiveMode]::Read, $true)
        try {
            $beaconReader = [IO.StreamReader]::new($beaconJar.GetEntry('META-INF/plugin.xml').Open())
            try { [xml]$beaconDescriptor = $beaconReader.ReadToEnd() } finally { $beaconReader.Dispose() }
            if ($beaconDescriptor.'idea-plugin'.id -ne 'dev.jvmbeacon' -or $beaconDescriptor.'idea-plugin'.version -ne $Version) { throw 'Packaged descriptor does not match the requested version/id.' }
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
    [xml]$beaconTest = Get-Content -LiteralPath $beaconFile.FullName
    foreach ($beaconKey in @('tests', 'failures', 'errors', 'skipped')) { $beaconTotals[$beaconKey] += [int]$beaconTest.testsuite.$beaconKey }
}
if ($beaconTotals.tests -lt 1 -or $beaconTotals.failures -ne 0 -or $beaconTotals.errors -ne 0 -or $beaconTotals.skipped -ne 0) { throw 'Missing, failing or skipped test results. Run the full Gradle test task first.' }
$beaconVerdicts = @()
foreach ($beaconIde in @('IC-251.26927.53', 'IU-251.26927.53')) {
    $beaconVerdict = (Get-Content -LiteralPath (Join-Path $beaconRoot "build/reports/pluginVerifier/$beaconIde/plugins/dev.jvmbeacon/$Version/verification-verdict.txt") -Raw).Trim()
    if (-not $beaconVerdict.StartsWith('Compatible.')) { throw "Verifier did not report Compatible for $beaconIde" }
    $beaconVerdicts += @{ ide = $beaconIde; verdict = $beaconVerdict }
}
$beaconIdeEvidence = @()
foreach ($beaconSandbox in $IdeSandboxPath) {
    # Close this sandbox first: the running IDE can lock or still be appending idea.log.
    $beaconLoaded = Get-FileHash -LiteralPath (Join-Path $beaconSandbox "plugins/jvm-beacon/lib/jvm-beacon-$Version.jar") -Algorithm SHA256
    if ($beaconLoaded.Hash.ToLowerInvariant() -ne $beaconJarHash) { throw "Sandbox JAR differs from the installation package: $beaconSandbox" }
    $beaconLines = [IO.File]::ReadAllLines((Join-Path $beaconSandbox 'log/idea.log'))
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
    packagedJarSha256 = $beaconJarHash; tests = $beaconTotals; verifier = $beaconVerdicts; ideSessions = $beaconIdeEvidence
    note = 'Automated package and log checks only. Manual GUI acceptance and test coverage are recorded separately; this script cannot certify them.'
}
$beaconOutput = Join-Path $beaconRoot "build/reports/release-checks-$Version.json"
$beaconResult | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $beaconOutput -Encoding UTF8
$beaconResult | ConvertTo-Json -Depth 6
Write-Host "RELEASE_PACKAGE_CHECK_PASS: $beaconOutput"
