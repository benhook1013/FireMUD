$ErrorActionPreference = 'Stop'

$TaskName = 'FireMUD Local Status Page'
$WindowsLocalDirectory = Join-Path $env:LOCALAPPDATA 'FireMUD\local-status-page'
$BootstrapPath = Join-Path $WindowsLocalDirectory 'start-local-status-page.ps1'
$ServerScript = '\\wsl.localhost\Ubuntu-22.04\home\ben\src\FireMUD-project-direction\tmp\local-status-page\start-wsl-status-page.ps1'
$CurrentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$PowerShell = Join-Path $PSHOME 'powershell.exe'

New-Item -ItemType Directory -Path $WindowsLocalDirectory -Force | Out-Null

$Bootstrap = @'
$ErrorActionPreference = 'Stop'

$TaskDirectory = Join-Path $env:LOCALAPPDATA 'FireMUD\local-status-page'
$LogFile = Join-Path $TaskDirectory 'startup.log'
$Wsl = Join-Path $env:WINDIR 'System32\wsl.exe'
$PowerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
$ServerScript = '\\wsl.localhost\Ubuntu-22.04\home\ben\src\FireMUD-project-direction\tmp\local-status-page\start-wsl-status-page.ps1'

function Write-StartupLog([string]$Message) {
    Add-Content -LiteralPath $LogFile -Value "$(Get-Date -Format o) $Message"
}

try {
    Write-StartupLog 'Starting Ubuntu-22.04 so its Windows file share is available.'
    $Ready = $false
    for ($Attempt = 1; $Attempt -le 6; $Attempt++) {
        $WslOutput = & $Wsl -d Ubuntu-22.04 --exec /bin/true 2>&1 | Out-String
        $WslExitCode = $LASTEXITCODE
        $ShareReady = Test-Path -LiteralPath $ServerScript
        Write-StartupLog "WSL attempt $Attempt exited $WslExitCode; script share available: $ShareReady; output: $($WslOutput.Trim())"
        if ($ShareReady) {
            $Ready = $true
            break
        }
        Start-Sleep -Seconds 5
    }
    if (-not $Ready) { throw 'Ubuntu-22.04 or the status page script did not become available.' }

    Write-StartupLog 'Starting the WSL status server and Windows TCP port forward.'
    & $PowerShell -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $ServerScript *>> $LogFile
    $ServerExitCode = $LASTEXITCODE
    if ($ServerExitCode -ne 0) { throw "WSL status server exited with code $ServerExitCode." }
    throw 'WSL status server exited unexpectedly.'
} catch {
    Write-StartupLog "Startup failed: $($_.Exception.Message)"
    exit 1
}
'@

Set-Content -LiteralPath $BootstrapPath -Value $Bootstrap -Encoding UTF8

$Action = New-ScheduledTaskAction -Execute $PowerShell -Argument "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$BootstrapPath`""
$Trigger = New-ScheduledTaskTrigger -AtLogOn -User $CurrentUser
$Principal = New-ScheduledTaskPrincipal -UserId $CurrentUser -LogonType Interactive -RunLevel Highest
$Settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings -Description 'Starts the WSL FireMUD status server and forwards the private LAN port to it.' -Force | Out-Null

Write-Output "Registered elevated same-user logon task '$TaskName' for the WSL server and TCP port forward."
Write-Output "Windows-local bootstrap: $BootstrapPath"
