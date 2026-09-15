#requires -Version 7.3
# Exercise the native Windows runtime and AppContainer process-jail boundary.
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if (-not $IsWindows) { throw 'Windows runtime execution tests require Windows.' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtime = if ($env:VIS_PYTHON_NATIVE_PATH) {
    if (Test-Path -LiteralPath $env:VIS_PYTHON_NATIVE_PATH -PathType Leaf) {
        Split-Path $env:VIS_PYTHON_NATIVE_PATH
    }
    else { $env:VIS_PYTHON_NATIVE_PATH }
}
else { Join-Path $repo 'resources/prebuilds/windows-x64' }
foreach ($name in @('vispython.dll', 'visjail.dll', 'vis-python-worker.exe', 'python/python.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtime $name) -PathType Leaf)) {
        throw "Windows execution cannot be skipped: missing $name in $runtime"
    }
}
$runtime = (Resolve-Path -LiteralPath $runtime).Path
$guest = Join-Path $repo 'target/windows-jail-guest.exe'
$launcher = Join-Path $repo 'target/windows-jail-probe.exe'
foreach ($path in @($guest, $launcher)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing Windows jail test executable: $path; run clojure -T:build windows-jail-probe"
    }
}

function Invoke-JailProbe([string] $Executable, [string[]] $Arguments) {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.UseShellExecute = $false
    # Native-image launchers need the unpacked DLL, not an embedded classpath resource.
    $start.Environment['VIS_PYTHON_NATIVE_PATH'] = $runtime
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Could not start Windows jail probe: $Executable" }
        if (-not $process.WaitForExit(180000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw "Windows jail probe timed out: $Executable"
        }
        if ($process.ExitCode -ne 0) {
            throw "Windows jail probe failed ($($process.ExitCode)): $Executable"
        }
    }
    finally { $process.Dispose() }
}

$namespaces = @(
    'com.blockether.vis-python-runtime-test'
    'com.blockether.vis-python-runtime.native-test'
    'com.blockether.vis-python-runtime.bridge-test'
    'com.blockether.vis-python-runtime.asyncio-test'
    'com.blockether.vis-python-runtime.test-diagnostics-test'
    'com.blockether.vis-python-runtime.host-test'
    'com.blockether.vis-python-runtime.log-test'
    'com.blockether.vis-python-runtime.threads-test'
    'com.blockether.vis-python-runtime.network-capability-test'
    'com.blockether.vis-python-runtime.native-reachability-test'
    'com.blockether.vis-python-runtime.windows-release-test'
    'com.blockether.vis-python-runtime.windows-test'
    'com.blockether.vis-python-runtime.windows-native-test'
)
$testArguments = @()
foreach ($namespace in $namespaces) { $testArguments += @('-n', $namespace) }
$oldGuest = $env:VIS_WINDOWS_JAIL_GUEST
Push-Location $repo
try {
    $env:VIS_WINDOWS_JAIL_GUEST = $guest
    $classPath = @('target/test-classes', 'target/classes', 'resources') -join [IO.Path]::PathSeparator
    Invoke-JailProbe -Executable (Get-Command java.exe).Source -Arguments @(
        '--enable-native-access=ALL-UNNAMED', '-cp', $classPath,
        'com.blockether.vispython.WindowsJailProbe', $guest, $runtime)
    Invoke-JailProbe -Executable $launcher -Arguments @($guest, $runtime)
    & clojure -M:test:test-diagnostics -n com.blockether.vis-python-runtime.windows-jail-test
    if ($LASTEXITCODE -ne 0) { throw "Windows process-jail tests failed: $LASTEXITCODE" }
    & javac -encoding UTF-8 -cp target/classes -d target/test-classes doc/examples/WindowsJailExample.java
    if ($LASTEXITCODE -ne 0) { throw "Windows jail documentation example failed to compile: $LASTEXITCODE" }
    Invoke-JailProbe -Executable (Get-Command java.exe).Source -Arguments @(
        '--enable-native-access=ALL-UNNAMED', '-cp', $classPath,
        'WindowsJailExample', $runtime, ([IO.Path]::GetTempPath()))
    # Isolate the embedded native boundary before the shared-interpreter suites.
    & clojure -M:test:test-diagnostics -n com.blockether.vis-python-runtime.windows-native-test
    if ($LASTEXITCODE -ne 0) { throw "Windows native boundary tests failed: $LASTEXITCODE" }
    # Keep the CLI alias literal: ClojureTools parses splatted alias tokens as filenames.
    & clojure -M:test:test-diagnostics @testArguments
    if ($LASTEXITCODE -ne 0) { throw "Windows runtime tests failed: $LASTEXITCODE" }
}
finally {
    $env:VIS_WINDOWS_JAIL_GUEST = $oldGuest
    Pop-Location
}
