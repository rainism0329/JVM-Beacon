param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
    throw 'Specify -JdkHome pointing to a complete JDK 21 (or set JAVA_HOME).'
}
$beaconClasses = Join-Path $beaconRoot 'build\timeline-demo-classes'
$beaconCapture = Join-Path $beaconRoot ('build\examples\timeline-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $beaconClasses | Out-Null
$beaconSources = @(Get-ChildItem -LiteralPath (Join-Path $beaconRoot 'src\main\java\dev\jvmbeacon\core') -Filter '*.java' | Select-Object -ExpandProperty FullName)
$beaconSources += Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\fixture\DemoApplication.java'
$beaconSources += Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\core\FixtureProcess.java'
$beaconSources += Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\core\TimelineCaptureMain.java'
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -encoding UTF-8 -d $beaconClasses @beaconSources
if ($LASTEXITCODE -ne 0) { throw 'Timeline demo compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -Xmx128m -cp $beaconClasses dev.jvmbeacon.core.TimelineCaptureMain $beaconCapture
if ($LASTEXITCODE -ne 0) { throw 'Timeline capture checks failed.' }
