param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
    throw 'Specify -JdkHome pointing to a complete JDK 21 (or set JAVA_HOME).'
}
$beaconOutput = Join-Path $beaconRoot 'build\standalone-core'
New-Item -ItemType Directory -Force -Path $beaconOutput | Out-Null
$beaconSources = @(Get-ChildItem -LiteralPath (Join-Path $beaconRoot 'src\main\java\dev\jvmbeacon\core') -Filter '*.java' | Select-Object -ExpandProperty FullName)
$beaconSources += Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\fixture\DemoApplication.java'
$beaconSources += Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\core\FixtureProcess.java'
$beaconSources += Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\core\CoreSmokeMain.java'
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -encoding UTF-8 -d $beaconOutput @beaconSources
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed.' }
Push-Location $beaconRoot
try {
    & (Join-Path $JdkHome 'bin\java.exe') -Xmx256m -cp $beaconOutput dev.jvmbeacon.core.CoreSmokeMain
    if ($LASTEXITCODE -ne 0) { throw 'Core smoke checks failed.' }
} finally { Pop-Location }
