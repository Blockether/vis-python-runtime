#requires -Version 7.3
# These suites exercise supported Windows capabilities, not the Unix OS jail.
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
foreach ($name in @('vispython.dll', 'vis-python-worker.exe', 'python/python.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtime $name) -PathType Leaf)) {
        throw "Windows execution cannot be skipped: missing $name in $runtime"
    }
}
$namespaces = @(
    'com.blockether.vis-python-runtime-test'
    'com.blockether.vis-python-runtime.native-test'
    'com.blockether.vis-python-runtime.bridge-test'
    'com.blockether.vis-python-runtime.host-test'
    'com.blockether.vis-python-runtime.log-test'
    'com.blockether.vis-python-runtime.threads-test'
    'com.blockether.vis-python-runtime.network-capability-test'
    'com.blockether.vis-python-runtime.native-reachability-test'
    'com.blockether.vis-python-runtime.windows-release-test'
    'com.blockether.vis-python-runtime.windows-test'
    'com.blockether.vis-python-runtime.windows-native-test'
)
$arguments = @('-M:test')
foreach ($namespace in $namespaces) { $arguments += @('-n', $namespace) }
Push-Location $repo
try {
    # Isolate the new native boundary before the shared-interpreter suites.
    & clojure -M:test -n com.blockether.vis-python-runtime.windows-native-test
    if ($LASTEXITCODE -ne 0) { throw "Windows native boundary tests failed: $LASTEXITCODE" }
    & clojure @arguments
    if ($LASTEXITCODE -ne 0) { throw "Windows runtime tests failed: $LASTEXITCODE" }
}
finally { Pop-Location }
