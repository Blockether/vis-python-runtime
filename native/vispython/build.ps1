#requires -Version 7.3
# Build the Windows x64 embedding runtime from hash-pinned upstream archives.
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

if (-not $IsWindows -or [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'This build requires Windows x64 and the Visual Studio x64 Native Tools environment.'
}
$null = Get-Command cl.exe -ErrorAction Stop
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function Read-Pin([string] $Name) {
    $values = @{}
    foreach ($line in Get-Content (Join-Path $repo $Name)) {
        if ($line -match '^([A-Z0-9_]+)="?([^"\r\n]+)"?$') {
            $values[$Matches[1]] = $Matches[2]
        }
    }
    return $values
}

function Get-VerifiedFile([string] $Url, [string] $Destination, [string] $Digest) {
    if ($Digest -notmatch '^[0-9a-f]{64}$') { throw "Invalid SHA-256 pin for $Url" }
    Invoke-WebRequest -Uri $Url -OutFile $Destination
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $Destination).Hash.ToLowerInvariant() -ne $Digest) {
        throw "SHA-256 mismatch for $Url"
    }
}

$pythonPin = Read-Pin '.cpython-version'
$uvPin = Read-Pin '.uv-version'
$out = Join-Path $repo 'resources/prebuilds/windows-x64'
$stage = Join-Path $repo ('target/windows-runtime-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $stage -Force
try {
    $pythonArchive = Join-Path $stage 'python.tar.gz'
    $pythonName = "cpython-$($pythonPin.CPYTHON_VERSION)%2B$($pythonPin.CPYTHON_RELEASE)-x86_64-pc-windows-msvc-install_only_stripped.tar.gz"
    Get-VerifiedFile -Url "https://github.com/astral-sh/python-build-standalone/releases/download/$($pythonPin.CPYTHON_RELEASE)/$pythonName" `
        -Destination $pythonArchive -Digest $pythonPin.CPYTHON_SHA256_WINDOWS_X64
    & tar.exe -xzf $pythonArchive -C $stage
    $pythonHome = Join-Path $stage 'python'
    $python = Join-Path $pythonHome 'python.exe'
    & $python -I -c 'import struct, sys; assert struct.calcsize("P") == 8; assert sys.version.split()[0] == sys.argv[1]; print(sys.version)' $pythonPin.CPYTHON_VERSION

    $uvArchive = Join-Path $stage 'uv.zip'
    Get-VerifiedFile -Url "https://github.com/astral-sh/uv/releases/download/$($uvPin.UV_VERSION)/uv-x86_64-pc-windows-msvc.zip" `
        -Destination $uvArchive -Digest $uvPin.UV_SHA256_WINDOWS_X64
    Expand-Archive -LiteralPath $uvArchive -DestinationPath (Join-Path $stage 'uv')
    $scripts = Join-Path $pythonHome 'Scripts'
    $null = New-Item -ItemType Directory -Path $scripts -Force
    Copy-Item (Join-Path $stage 'uv/uv.exe') (Join-Path $scripts 'uv.exe')
    $uvVersion = & (Join-Path $scripts 'uv.exe') --version
    if (($uvVersion -split ' \(')[0] -ne "uv $($uvPin.UV_VERSION)") { throw "Unexpected uv version: $uvVersion" }
    $licenses = Join-Path $stage 'licenses'
    $null = New-Item -ItemType Directory -Path $licenses
    foreach ($kind in @('MIT', 'APACHE')) {
        Get-VerifiedFile -Url "https://raw.githubusercontent.com/astral-sh/uv/$($uvPin.UV_VERSION)/LICENSE-$kind" `
            -Destination (Join-Path $licenses "uv-LICENSE-$kind") -Digest $uvPin["UV_SHA256_LICENSE_$kind"]
    }

    $minor = ($pythonPin.CPYTHON_VERSION -split '\.')[0..1] -join ''
    $library = Join-Path $stage 'vispython.dll'
    & cl.exe /nologo /std:c11 /LD /MD /O2 /W4 /D_CRT_SECURE_NO_WARNINGS "/I$pythonHome/include" "/Fo$stage/vispython.obj" "/Fe$library" (Join-Path $PSScriptRoot 'vispython.c') /link "/LIBPATH:$pythonHome/libs" "python$minor.lib"
    if (-not (Test-Path -LiteralPath $library -PathType Leaf)) { throw 'MSVC produced no vispython.dll' }
    Get-ChildItem -LiteralPath $pythonHome -Directory -Recurse -Filter '__pycache__' |
        Remove-Item -Recurse -Force
    $null = New-Item -ItemType Directory -Path (Split-Path $out) -Force
    if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
    $null = New-Item -ItemType Directory -Path $out
    Move-Item -LiteralPath $pythonHome -Destination (Join-Path $out 'python')
    Move-Item -LiteralPath $licenses -Destination (Join-Path $out 'licenses')
    Move-Item -LiteralPath $library -Destination (Join-Path $out 'vispython.dll')
    Write-Output (Join-Path $out 'vispython.dll')
}
finally {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
