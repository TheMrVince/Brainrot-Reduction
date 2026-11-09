<#
Removes the scheduled tasks and the copied programs installed by install.ps1.
Your config, saved state and saved policy in ~\.brainrot-reduction are left alone.

  powershell -ExecutionPolicy Bypass -File packaging\windows\uninstall.ps1 [-Prefix NAME] [-AppDir DIR]
#>
[CmdletBinding()]
param(
    [string]$Prefix = 'Brainrot Reduction',
    [string]$AppDir = (Join-Path $env:USERPROFILE '.brainrot-reduction\app')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

if ($env:OS -ne 'Windows_NT') {
    [Console]::Error.WriteLine('uninstall: this script is for Windows')
    exit 1
}

foreach ($name in @("$Prefix Controller", "$Prefix Enforcer")) {
    if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $name
        Unregister-ScheduledTask -TaskName $name -Confirm:$false
        Write-Host "removed task '$name'"
    }
}

$enforcerExe = Join-Path $AppDir 'enforcerw.exe'
$controllerDir = Join-Path $AppDir 'controller'
Get-Process enforcerw -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $enforcerExe } | Stop-Process -Force
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($controllerDir) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Milliseconds 500

if (Test-Path $enforcerExe) { Remove-Item -LiteralPath $enforcerExe -Force }
if (Test-Path $controllerDir) { Remove-Item -LiteralPath $controllerDir -Recurse -Force }
Write-Host 'Removed. Your config and saved data in ~\.brainrot-reduction were kept.'
