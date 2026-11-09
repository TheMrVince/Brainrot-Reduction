<#
Installs Brainrot Reduction on Windows as two per-user scheduled tasks (no admin rights needed):

  "<Prefix> Enforcer"    enforcerw serve   starts at login, and Task Scheduler starts it again within
                                           a minute if it stops, so killing it only delays things
  "<Prefix> Controller"  controller tray   starts at login; "Quit" in its menu keeps it quit until the
                                           next login

Neither opens a console window: the enforcer is enforcerw.exe (the console-less build) and the
controller runs under javaw.exe. The programs are copied to a stable folder, so a later clean build
cannot break the start at login. Run it again to update. Remove everything with uninstall.ps1.

Windows blocks unsigned scripts by default, so run it like this:

  powershell -ExecutionPolicy Bypass -File packaging\windows\install.ps1 [options]

Options:
  -NoBuild                 use the existing builds instead of building first
  -Controller MODE         tray (default), run (no icon), or none (enforcer only)
  -Prefix NAME             task name prefix (default "Brainrot Reduction")
  -AppDir DIR              where the programs are copied (default ~\.brainrot-reduction\app)
  -LogDir DIR              where the enforcer writes its log (default ~\.brainrot-reduction\logs)
  -EnforcerArgs A,B        extra arguments for `enforcer serve`
  -ControllerArgs A,B      extra arguments for the controller
#>
[CmdletBinding()]
param(
    [switch]$NoBuild,
    [ValidateSet('tray', 'run', 'none')][string]$Controller = 'tray',
    [string]$Prefix = 'Brainrot Reduction',
    [string]$AppDir = (Join-Path $env:USERPROFILE '.brainrot-reduction\app'),
    [string]$LogDir = (Join-Path $env:USERPROFILE '.brainrot-reduction\logs'),
    [string[]]$EnforcerArgs = @(),
    [string[]]$ControllerArgs = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

function Die([string]$message) {
    [Console]::Error.WriteLine("install: $message")
    exit 1
}

# Task Scheduler takes one argument string; quote the parts that need it.
function Join-Arguments([string[]]$parts) {
    ($parts | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join ' '
}

function Wait-Until([scriptblock]$condition, [int]$seconds = 10) {
    for ($i = 0; $i -lt $seconds * 4; $i++) {
        if (& $condition) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

if ($env:OS -ne 'Windows_NT') { Die 'this script is for Windows; use packaging/macos/install.sh on macOS' }

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$DataDir = Join-Path $env:USERPROFILE '.brainrot-reduction'
$User = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$EnforcerTask = "$Prefix Enforcer"
$ControllerTask = "$Prefix Controller"
$EnforcerBuilt = Join-Path $Root 'enforcer\build\Release\enforcerw.exe'
$ControllerDist = Join-Path $Root 'controller\build\install\controller'
$EnforcerExe = Join-Path $AppDir 'enforcerw.exe'
$ControllerDir = Join-Path $AppDir 'controller'

$Javaw = $null
if ($Controller -ne 'none') {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\javaw.exe' }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { $candidates += Join-Path (Split-Path $java.Source) 'javaw.exe' }
    $Javaw = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $Javaw) { Die 'no JDK found; install JDK 21 or newer' }
    if ($ControllerArgs.Count -eq 0 -and -not (Test-Path (Join-Path $DataDir 'config.json'))) {
        Die "no config at $DataDir\config.json; copy config\config.example.json there and edit it first"
    }
}

if (-not $NoBuild) {
    Write-Host 'Building the enforcer...'
    & cmake -S "$Root\enforcer" -B "$Root\enforcer\build" | Out-Null
    if ($LASTEXITCODE) { Die 'configuring the enforcer build failed' }
    & cmake --build "$Root\enforcer\build" --config Release --target enforcerw | Out-Null
    if ($LASTEXITCODE) { Die 'building the enforcer failed' }
    if ($Controller -ne 'none') {
        Write-Host 'Building the controller...'
        Push-Location $Root
        try { & .\gradlew.bat -q :controller:installDist } finally { Pop-Location }
        if ($LASTEXITCODE) { Die 'building the controller failed' }
    }
}
if (-not (Test-Path $EnforcerBuilt)) { Die "the enforcer is not built ($EnforcerBuilt); run without -NoBuild" }
if ($Controller -ne 'none' -and -not (Test-Path (Join-Path $ControllerDist 'lib'))) {
    Die "the controller is not built ($ControllerDist); run without -NoBuild"
}

Write-Host 'Stopping any earlier install...'
foreach ($name in @($ControllerTask, $EnforcerTask)) {
    if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $name
        Unregister-ScheduledTask -TaskName $name -Confirm:$false
    }
}
# Copies started by hand, or still shutting down, would keep the files locked.
Get-Process enforcerw -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $EnforcerExe } | Stop-Process -Force
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($ControllerDir) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
if (-not (Wait-Until { -not (Get-Process enforcerw -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $EnforcerExe }) })) {
    Die 'could not stop the running enforcer'
}

New-Item -ItemType Directory -Force -Path $AppDir, $LogDir | Out-Null
Copy-Item -LiteralPath $EnforcerBuilt -Destination $EnforcerExe -Force
if ($Controller -ne 'none') {
    if (Test-Path $ControllerDir) { Remove-Item -LiteralPath $ControllerDir -Recurse -Force }
    Copy-Item -LiteralPath $ControllerDist -Destination $ControllerDir -Recurse
}

$principal = New-ScheduledTaskPrincipal -UserId $User -LogonType Interactive -RunLevel Limited

# Enforcer: at login, and every minute from now on. With IgnoreNew a run that finds it already running
# does nothing, so the repetition only matters after the enforcer has stopped. The repetition sits on a
# time trigger, not the logon trigger: a logon trigger's repetition only starts when that trigger
# fires, so it would do nothing until the next login (seen when testing on Windows 11).
$enforcerAction = New-ScheduledTaskAction -Execute $EnforcerExe -WorkingDirectory $AppDir `
    -Argument (Join-Arguments (@('serve', '--log-file', (Join-Path $LogDir 'enforcer.log')) + $EnforcerArgs))
$everyMinute = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 1)
$enforcerTrigger = @((New-ScheduledTaskTrigger -AtLogOn -User $User), $everyMinute)
$enforcerSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -StartWhenAvailable
Register-ScheduledTask -TaskName $EnforcerTask -Action $enforcerAction -Trigger $enforcerTrigger `
    -Settings $enforcerSettings -Principal $principal -Force `
    -Description 'Brainrot Reduction: closes blocked apps until today''s LeetCode problem is solved.' | Out-Null

if ($Controller -ne 'none') {
    # Controller: at login only, so "Quit" stays quit, as on macOS.
    $classPath = Join-Path $ControllerDir 'lib\*'
    $controllerAction = New-ScheduledTaskAction -Execute $Javaw -WorkingDirectory $AppDir `
        -Argument (Join-Arguments (@('-cp', $classPath, 'com.brainrot.controller.Main', $Controller) + $ControllerArgs))
    $controllerTrigger = New-ScheduledTaskTrigger -AtLogOn -User $User
    $controllerSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $ControllerTask -Action $controllerAction -Trigger $controllerTrigger `
        -Settings $controllerSettings -Principal $principal -Force `
        -Description 'Brainrot Reduction: checks LeetCode and tells the enforcer whether to block.' | Out-Null
}

Write-Host 'Starting...'
foreach ($name in @($EnforcerTask) + $(if ($Controller -ne 'none') { @($ControllerTask) } else { @() })) {
    Start-ScheduledTask -TaskName $name
    if (-not (Wait-Until { (Get-ScheduledTask -TaskName $name).State -eq 'Running' })) {
        Die "$name did not start; look in $LogDir and in Task Scheduler"
    }
}

Write-Host ''
Write-Host 'Installed. Both start at every login; the enforcer is started again if it stops.'
Write-Host "  enforcer    task '$EnforcerTask'"
if ($Controller -ne 'none') { Write-Host "  controller  task '$ControllerTask' ($Controller)" }
Write-Host "  programs    $AppDir"
Write-Host "  logs        $LogDir"
$config = Join-Path $DataDir 'config.json'
if ($Controller -ne 'none' -and $ControllerArgs.Count -eq 0 -and (Test-Path $config)) {
    if ((Get-Content -Raw $config) -match '"dryRun"\s*:\s*false') {
        Write-Host '  blocking    REAL: blocked apps will be closed'
    } else {
        Write-Host '  blocking    dry run only; set "dryRun": false in config.json when you are ready'
    }
}
Write-Host 'To remove: powershell -ExecutionPolicy Bypass -File packaging\windows\uninstall.ps1'
