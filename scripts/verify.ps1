[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
if ($env:JAVA_HOME) { $env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path }

function Invoke-Check([string]$directory, [string]$command, [string[]]$arguments) {
    Push-Location (Join-Path $root $directory)
    try {
        & $command @arguments
        if ($LASTEXITCODE -ne 0) { throw "Verification failed: $command $($arguments -join ' ')" }
    } finally { Pop-Location }
}

$wrapper = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { '.\mvnw.cmd' } else { './mvnw' }
Invoke-Check 'backend' $wrapper @('-q', 'verify')
& (Join-Path $PSScriptRoot 'audit-backend.ps1')
Invoke-Check 'frontend' 'pnpm' @('test')
Invoke-Check 'frontend' 'pnpm' @('build')
Invoke-Check 'frontend' 'pnpm' @('audit', '--registry=https://registry.npmjs.org')
Write-Host 'All verification steps passed.'
