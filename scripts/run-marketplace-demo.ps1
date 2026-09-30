param(
    [string]$JdkHome = $env:JAVA_HOME,
    [ValidateRange(1, 600)][int]$DurationSeconds = 600
)
$ErrorActionPreference = 'Stop'
$beaconRoot = Split-Path -Parent $PSScriptRoot
$beaconPasswordChars = $null
$beaconSecurePassword = $null
$beaconPasswordPointer = [IntPtr]::Zero
$beaconProcess = $null
$beaconStarted = $false

try {
    # This optional variable is one-shot input. Remove it before javac/java are
    # created so it is not inherited by either child. No machine/user env is edited.
    $beaconEnvironmentPassword = [Environment]::GetEnvironmentVariable('BEACON_DEMO_PASSWORD', 'Process')
    Remove-Item -LiteralPath Env:BEACON_DEMO_PASSWORD -ErrorAction SilentlyContinue
    if ($null -ne $beaconEnvironmentPassword) {
        $beaconPasswordChars = $beaconEnvironmentPassword.ToCharArray()
        $beaconEnvironmentPassword = $null
    } else {
        $beaconSecurePassword = Read-Host 'Temporary demo password (at least 8 characters; not stored)' -AsSecureString
        $beaconPasswordChars = [char[]]::new($beaconSecurePassword.Length)
        $beaconPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($beaconSecurePassword)
        [Runtime.InteropServices.Marshal]::Copy($beaconPasswordPointer, $beaconPasswordChars, 0, $beaconPasswordChars.Length)
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($beaconPasswordPointer)
        $beaconPasswordPointer = [IntPtr]::Zero
        $beaconSecurePassword.Dispose(); $beaconSecurePassword = $null
    }
    if ($beaconPasswordChars.Length -lt 8 -or $beaconPasswordChars.Length -gt 1024) { throw 'Use a temporary demo password between 8 and 1024 characters.' }
    if ($beaconPasswordChars -contains [char]0 -or $beaconPasswordChars -contains [char]10 -or $beaconPasswordChars -contains [char]13) { throw 'The stdin password protocol does not accept NUL or newline characters.' }
    if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) { throw 'Specify -JdkHome pointing to a complete JDK 21 (or set JAVA_HOME).' }
    $beaconClasses = Join-Path $beaconRoot 'build\fixture-classes'
    [IO.Directory]::CreateDirectory($beaconClasses) | Out-Null
    $beaconSource = Join-Path $beaconRoot 'src\test\java\dev\jvmbeacon\fixture\DemoApplication.java'
    & (Join-Path $JdkHome 'bin\javac.exe') --release 21 -encoding UTF-8 -d $beaconClasses $beaconSource
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed. A JDK 21 or later compiler is required.' }

    $beaconStart = [Diagnostics.ProcessStartInfo]::new()
    $beaconStart.FileName = Join-Path $JdkHome 'bin\java.exe'
    $beaconStart.UseShellExecute = $false
    $beaconStart.CreateNoWindow = $true
    $beaconStart.RedirectStandardInput = $true
    $beaconStart.EnvironmentVariables.Remove('BEACON_DEMO_PASSWORD')
    # No shell executes this string. Windows path names cannot contain quotes;
    # the generated class directory has no trailing slash requiring extra escaping.
    $beaconStart.Arguments = '-Xms32m -Xmx96m -cp "' + $beaconClasses + '" dev.jvmbeacon.fixture.DemoApplication --remote --public-demo --duration=' + $DurationSeconds
    $beaconProcess = [Diagnostics.Process]::new()
    $beaconProcess.StartInfo = $beaconStart
    Write-Host "Authenticated loopback demo only; automatic stop after $DurationSeconds seconds."
    Write-Host 'Connect using the JMX_URL below, operator (read/write) or observer (server-enforced read-only), and the temporary password you supplied.'
    Write-Host 'The display-only Runtime.Name host is beacon-demo; metrics, MBeans and diagnostics remain real. This script does not connect to existing JVMs.'
    $beaconStarted = $beaconProcess.Start()
    if (-not $beaconStarted) { throw 'Could not start the owned demo JVM.' }
    $beaconClock = [Diagnostics.Stopwatch]::StartNew()
    # Explicit UTF-8 matches the JDK 21 default even on hosts whose console uses
    # a legacy code page. Do not construct another immutable password String.
    $beaconInputWriter = [IO.StreamWriter]::new($beaconProcess.StandardInput.BaseStream, [Text.UTF8Encoding]::new($false))
    try {
        $beaconInputWriter.Write($beaconPasswordChars, 0, $beaconPasswordChars.Length)
        $beaconInputWriter.WriteLine()
        $beaconInputWriter.Flush()
    } finally { $beaconInputWriter.Dispose() }
    [Array]::Clear($beaconPasswordChars, 0, $beaconPasswordChars.Length)
    $beaconPasswordChars = $null
    while (-not $beaconProcess.WaitForExit(250)) {
        if ($beaconClock.Elapsed.TotalSeconds -gt $DurationSeconds + 20) { throw 'Demo exceeded the bounded startup/runtime allowance; stopping its owned child JVM.' }
    }
    if ($beaconProcess.ExitCode -ne 0) { throw "Demo JVM exited with code $($beaconProcess.ExitCode)." }
    Write-Host 'MARKETPLACE_DEMO_CLOSED: owned JVM exited.'
} finally {
    if ($null -ne $beaconPasswordChars) { [Array]::Clear($beaconPasswordChars, 0, $beaconPasswordChars.Length); $beaconPasswordChars = $null }
    if ($beaconPasswordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($beaconPasswordPointer) }
    if ($null -ne $beaconSecurePassword) { $beaconSecurePassword.Dispose() }
    $beaconEnvironmentPassword = $null
    if ($null -ne $beaconProcess) {
        try {
            if ($beaconStarted -and -not $beaconProcess.HasExited) {
                # Only the child represented by this process handle is terminated.
                $beaconProcess.Kill()
                if (-not $beaconProcess.WaitForExit(5000)) { Write-Warning 'Owned demo JVM did not confirm exit within 5 seconds.' }
            }
        } finally { $beaconProcess.Dispose() }
    }
}
