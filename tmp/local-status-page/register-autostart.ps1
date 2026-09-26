param([switch] $InstallFilesOnly)

$ErrorActionPreference = 'Stop'

$TaskName = 'FireMUD Local Status Page'
$WindowsLocalDirectory = Join-Path $env:LOCALAPPDATA 'FireMUD\local-status-page'
$BootstrapPath = Join-Path $WindowsLocalDirectory 'start-local-status-page.ps1'
$CurrentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$PowerShell = Join-Path $PSHOME 'powershell.exe'

New-Item -ItemType Directory -Path $WindowsLocalDirectory -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'configure-portproxy.ps1') -Destination $WindowsLocalDirectory -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'allow-lan-firewall.ps1') -Destination $WindowsLocalDirectory -Force

$Bootstrap = @'
$ErrorActionPreference = 'Stop'

$TaskDirectory = Join-Path $env:LOCALAPPDATA 'FireMUD\local-status-page'
$LogFile = Join-Path $TaskDirectory 'startup.log'
$Wsl = Join-Path $env:WINDIR 'System32\wsl.exe'

try {
    & (Join-Path $TaskDirectory 'allow-lan-firewall.ps1') | Out-String | Add-Content -LiteralPath $LogFile
    $network = & $Wsl -d Ubuntu-22.04 --exec /sbin/ip -4 -o addr show dev eth0 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not read the WSL eth0 address: $($network -join ' ')" }
    $match = [regex]::Match(($network -join ' '), '\binet\s+(\d+\.\d+\.\d+\.\d+)/')
    if (-not $match.Success) { throw "No IPv4 address was found on WSL eth0: $($network -join ' ')" }
    & (Join-Path $TaskDirectory 'configure-portproxy.ps1') -ConnectAddress $match.Groups[1].Value |
        Out-String | Add-Content -LiteralPath $LogFile
    Add-Content -LiteralPath $LogFile -Value "$(Get-Date -Format o) Windows TCP forward ready; the status server is owned by WSL systemd."
} catch {
    Add-Content -LiteralPath $LogFile -Value "$(Get-Date -Format o) Port-forward setup failed: $($_.Exception.Message)"
    exit 1
}
'@

Set-Content -LiteralPath $BootstrapPath -Value $Bootstrap -Encoding UTF8
if ($InstallFilesOnly) {
    Write-Output "Updated Windows-only forwarding bootstrap: $BootstrapPath"
    return
}

$Action = New-ScheduledTaskAction -Execute $PowerShell -Argument "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$BootstrapPath`""
$Trigger = New-ScheduledTaskTrigger -AtLogOn -User $CurrentUser
$Principal = New-ScheduledTaskPrincipal -UserId $CurrentUser -LogonType Interactive -RunLevel Highest
$Settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings -Description 'Maintains only the private LAN firewall and TCP forward to the WSL-owned FireMUD status page.' -Force | Out-Null

Write-Output "Registered elevated same-user logon task '$TaskName' for the Windows TCP forward only."
Write-Output "Windows-local bootstrap: $BootstrapPath"
