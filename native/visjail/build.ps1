#requires -Version 7.3
# Build the Windows x64 private-workspace process jail with the Windows SDK.
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

if (-not $IsWindows -or [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'This build requires Windows x64 and the Visual Studio x64 Native Tools environment.'
}
$null = Get-Command cl.exe -ErrorAction Stop
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$out = (Resolve-Path -LiteralPath $OutputDirectory).Path
$stage = Join-Path $repo ('target/windows-jail-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $stage
$exports = @(
    'spawn', 'read', 'write', 'close', 'poll', 'wait', 'kill',
    'windows_create', 'windows_stage', 'windows_seal', 'windows_destroy', 'windows_pid'
) | ForEach-Object { "/EXPORT:visjail_$_" }
try {
    $library = Join-Path $out 'visjail.dll'
    & cl.exe /nologo /std:c11 /LD /MD /O2 /W4 /WX /D_CRT_SECURE_NO_WARNINGS `
        "/Fo$stage/visjail.obj" "/Fe$library" (Join-Path $PSScriptRoot 'visjail_windows.c') `
        /link "/IMPLIB:$stage/visjail.lib" @exports advapi32.lib userenv.lib bcrypt.lib
    if (-not (Test-Path -LiteralPath $library -PathType Leaf)) { throw 'MSVC produced no visjail.dll' }
    Write-Output $library
}
finally {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
