param(
    [string]$JdkHome = $env:JAVA_HOME,
    [ValidateRange(0, 600)][int]$DurationSeconds = 0,
    [switch]$CpuDemo
)
$ErrorActionPreference = 'Stop'
if ($CpuDemo -and $DurationSeconds -eq 0) { throw 'Use -DurationSeconds 120 (1–600) with -CpuDemo. CPU pulses stop within 120 seconds.' }
$beaconRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
    throw 'Specify -JdkHome pointing to a complete JDK 21 (or set JAVA_HOME).'
}
$beaconClasses = Join-Path $beaconRoot 'build\fixture-classes'
New-Item -ItemType Directory -Force -Path $beaconClasses | Out-Null
$beaconSource = Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\fixture\DemoApplication.java'
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -encoding UTF-8 -d $beaconClasses $beaconSource
if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed. A JDK 21 or later compiler is required.' }
$beaconArguments = @('-Xms32m', '-Xmx96m', '-cp', $beaconClasses, 'dev.jvmbeacon.fixture.DemoApplication', '--local', '--interactive')
if ($DurationSeconds -gt 0) { $beaconArguments += "--duration=$DurationSeconds" }
if ($CpuDemo) { $beaconArguments += '--cpu-demo' }
Write-Host 'Local fixture only: no remote JMX port. Connect to the PID below in JVM Beacon.'
& (Join-Path $JdkHome 'bin\java.exe') @beaconArguments
if ($LASTEXITCODE -ne 0) { throw "Fixture exited with code $LASTEXITCODE" }
