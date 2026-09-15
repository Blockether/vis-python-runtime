#requires -Version 7.3
[CmdletBinding()]
param([string]$EnvironmentFile = $env:GITHUB_ENV)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

if ([string]::IsNullOrWhiteSpace($EnvironmentFile) -or
        -not [System.IO.Path]::IsPathRooted($EnvironmentFile)) {
    throw 'An absolute GitHub environment file path is required.'
}

$before = [System.Environment]::GetEnvironmentVariables('Process')
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$installations = @(& $vswhere -latest -products '*' `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath)
if ($LASTEXITCODE -ne 0 -or $installations.Count -ne 1 -or
        [string]::IsNullOrWhiteSpace($installations[0])) {
    throw 'Visual Studio with the x64 C++ toolchain was not found.'
}

$installation = $installations[0].Trim()
Import-Module (Join-Path $installation 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $installation -SkipAutomaticLocation `
    -DevCmdArguments '-arch=x64 -host_arch=x64'
if ($env:VSCMD_ARG_TGT_ARCH -ne 'x64' -or $env:VSCMD_ARG_HOST_ARCH -ne 'x64') {
    throw 'Visual Studio did not activate the requested x64 toolchain.'
}
foreach ($tool in @('cl.exe', 'link.exe')) {
    $command = Get-Command $tool -CommandType Application
    if (-not $command.Source.StartsWith($installation + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "The selected $tool is not from the activated Visual Studio installation."
    }
}

# Export only changes made by DevShell, never the ambient CI environment.
$changes = [System.Collections.Generic.List[string]]::new()
foreach ($entry in [System.Environment]::GetEnvironmentVariables('Process').GetEnumerator()) {
    if ($before[$entry.Key] -ceq $entry.Value) { continue }
    if ($entry.Key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or $entry.Value -match '[\r\n]') {
        throw 'A toolchain environment entry cannot be represented as a single line.'
    }
    $changes.Add("$($entry.Key)=$($entry.Value)")
}
[System.IO.File]::AppendAllLines($EnvironmentFile, $changes, [System.Text.UTF8Encoding]::new($false))
Write-Output 'Configured the MSVC x64 build environment.'
