param(
    [string]$JdkHome = $env:JAVA_HOME,
    [ValidateRange(20, 600)][int]$DurationSeconds = 180
)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
    throw 'Specify -JdkHome pointing to a complete JDK 21 (or set JAVA_HOME).'
}
$beaconClasses = Join-Path $beaconRoot 'build\resource-soak-classes'
$beaconReport = Join-Path $beaconRoot ('build\reports\resource-soak\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $beaconClasses, $beaconReport | Out-Null
$beaconSources = @(Get-ChildItem -LiteralPath (Join-Path $beaconRoot 'src\main\java\dev\jvmbeacon\core') -Filter '*.java' | Select-Object -ExpandProperty FullName)
$beaconSources += @(
    (Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\fixture\DemoApplication.java'),
    (Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\core\FixtureProcess.java'),
    (Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\core\ResourceSoakMain.java')
)
& (Join-Path $JdkHome 'bin\javac.exe') '-J-Duser.language=en' '-J-Duser.country=US' --release 21 -encoding UTF-8 -d $beaconClasses @beaconSources
if ($LASTEXITCODE -ne 0) { throw 'Resource soak compilation failed.' }
Write-Host "Sampling for $DurationSeconds seconds, plus bounded warmup/cleanup; this is not whole-IDE profiling."
& (Join-Path $JdkHome 'bin\java.exe') '-Xms32m' '-Xmx128m' '-cp' $beaconClasses 'dev.jvmbeacon.core.ResourceSoakMain' $DurationSeconds $beaconReport
if ($LASTEXITCODE -ne 0) { throw "Resource soak did not complete; inspect $beaconReport\summary.md (exit $LASTEXITCODE)." }
Write-Host "Resource observation report: $beaconReport\summary.md"
