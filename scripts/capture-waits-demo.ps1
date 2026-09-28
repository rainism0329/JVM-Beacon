param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) { throw 'Specify -JdkHome pointing to a complete JDK 21.' }
$beaconClasses = Join-Path $beaconRoot 'build\waits-demo-classes'
$beaconOutput = Join-Path $beaconRoot ('build\examples\waits-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $beaconClasses, $beaconOutput | Out-Null
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -encoding UTF-8 -d $beaconClasses (Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\fixture\WaitRecordingFixture.java')
if ($LASTEXITCODE -ne 0) { throw 'Wait fixture compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -Xmx64m -cp $beaconClasses dev.jvmbeacon.fixture.WaitRecordingFixture (Join-Path $beaconOutput 'waits.jfr')
if ($LASTEXITCODE -ne 0) { throw 'Wait recording failed.' }
Write-Output "Owned child exited. Open in JVM Beacon > Flight Recorder > Open local .jfr: $beaconOutput\waits.jfr"
