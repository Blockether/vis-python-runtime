#requires -Version 7.3
# Execute the complete extracted Windows artifact without borrowing host Python.
[CmdletBinding()]
param([ValidateSet('windows-x64')][string] $Platform = 'windows-x64')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if (-not $IsWindows) { throw 'Windows archive execution must run on Windows.' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version = (Get-Content (Join-Path $repo 'VIS_PYTHON_VERSION') -Raw).Trim()
$archive = Join-Path $repo "target/vis-python-runtime-$Platform-$version.tar.gz"
$unpacked = Join-Path $repo "target/archive-check-$Platform"
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw "Missing $archive" }
if (Test-Path -LiteralPath $unpacked) { Remove-Item -LiteralPath $unpacked -Recurse -Force }
$null = New-Item -ItemType Directory -Path $unpacked
& tar.exe -xzf $archive -C $unpacked
foreach ($relative in @('vispython.dll', 'visjail.dll', 'vis-python-worker.exe', 'python/python.exe',
        'python/python314.dll', 'python/Lib/os.py', 'python/Scripts/uv.exe',
        'licenses/uv-LICENSE-MIT', 'licenses/uv-LICENSE-APACHE')) {
    if (-not (Test-Path -LiteralPath (Join-Path $unpacked $relative) -PathType Leaf)) {
        throw "Incomplete Windows runtime: $relative"
    }
}
$uvPin = Get-Content (Join-Path $repo '.uv-version') | Where-Object { $_ -match '^UV_VERSION=' }
$uvExpected = ($uvPin -split '=', 2)[1].Trim()
$uv = Join-Path $unpacked 'python/Scripts/uv.exe'
$python = Join-Path $unpacked 'python/python.exe'
$project = Join-Path $repo ('target/windows-archive-project-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $project
$oldPath = $env:PATH
$oldHome = $env:HOME
try {
    # Keep Windows system libraries available, but no host Python, uv or compiler.
    $env:PATH = "$env:SystemRoot/System32"
    $env:HOME = $project
    $uvVersion = & $uv --version
    if (($uvVersion -split ' \(')[0] -ne "uv $uvExpected") { throw "Unexpected uv version: $uvVersion" }
    '[project]', 'name = "archive-check"', 'version = "0.0.0"', 'requires-python = ">=3.10"' |
        Set-Content (Join-Path $project 'pyproject.toml') -Encoding utf8
    $uvArguments = @('--offline', '--no-cache', '--no-config', '--directory', $project)
    & $uv @uvArguments sync --python $python --no-python-downloads
    & $uv @uvArguments sync --check
    & $uv @uvArguments run --no-sync python -I -c 'import pathlib, ssl, sqlite3, sys; assert sys.prefix != sys.base_prefix; assert pathlib.Path(sys.base_prefix).samefile(sys.argv[1]); print("bundled Windows uv + Python: ok")' (Join-Path $unpacked 'python')
    $workerVersion = & (Join-Path $unpacked 'vis-python-worker.exe') --version
    if ($workerVersion.Trim() -ne $version) { throw "Unexpected worker version: $workerVersion" }
}
finally {
    $env:PATH = $oldPath
    $env:HOME = $oldHome
    Remove-Item -LiteralPath $project -Recurse -Force
}
Write-Output $archive
