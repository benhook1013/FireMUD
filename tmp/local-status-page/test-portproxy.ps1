$ErrorActionPreference = 'Stop'

$Helper = Join-Path $PSScriptRoot 'configure-portproxy.ps1'
$Target = '172.29.153.2'
$Previous = '172.29.153.1'
$ListenAddress = '192.168.50.100'
$ListenPort = 8877
$Owner = [ordered]@{
    Owner = 'FireMUD Local Status Page'
    Schema = 1
    ListenAddress = $ListenAddress
    ListenPort = $ListenPort
    ConnectAddress = $Target
    ConnectPort = $ListenPort
} | ConvertTo-Json -Compress
$PreviousOwner = $Owner.Replace($Target, $Previous)

function Get-Plan([string] $ConnectAddress, [string] $Mapping, [string] $OwnerRecord,
    [int[]] $ListenerPids = @(), [int] $IpHelperPid = 0,
    [int] $AddExitCode = -1, [int] $RestoreExitCode = -1) {
    $arguments = @{
        PlanOnly = $true
        ConnectAddress = $ConnectAddress
        ListenAddress = $ListenAddress
        ListenPort = $ListenPort
        ConnectPort = $ListenPort
    }
    if ($Mapping) { $arguments.TestExistingMapping = $Mapping }
    if ($OwnerRecord) { $arguments.TestOwnerRecord = $OwnerRecord }
    if ($ListenerPids.Count -gt 0) { $arguments.TestListenerPids = $ListenerPids }
    if ($IpHelperPid -gt 0) { $arguments.TestIpHelperPid = $IpHelperPid }
    if ($AddExitCode -ge 0) { $arguments.TestAddExitCode = $AddExitCode; $arguments.TestRestoreExitCode = $RestoreExitCode }
    return (& $Helper @arguments | Out-String).Trim()
}

$sameTarget = '{"ListenAddress":"192.168.50.100","ListenPort":"8877","ConnectAddress":"172.29.153.2","ConnectPort":"8877"}'
$oldTarget = '{"ListenAddress":"192.168.50.100","ListenPort":"8877","ConnectAddress":"172.29.153.1","ConnectPort":"8877"}'

Write-Output 'Checking idempotent target'
if ((Get-Plan $Target $sameTarget $Owner @(4242) 4242) -ne 'AlreadyCurrent|foreign-listeners=0') {
    throw 'Same-target startup with the persistent IP Helper listener must be idempotent.'
}
if ((Get-Plan $Target $sameTarget $Owner @(4242, 51) 4242) -ne 'AlreadyCurrent|foreign-listeners=1') {
    throw 'A non-IP Helper listener must remain a conflict.'
}
Write-Output 'Checking WSL IP update'
if ((Get-Plan $Target $oldTarget $PreviousOwner) -ne 'UpdateOwned|foreign-listeners=0') { throw 'An owned NAT target must update after WSL IP drift.' }
Write-Output 'Checking update rollback reports'
$restored = Get-Plan $Target $oldTarget $PreviousOwner @() 0 1 0
if ($restored -ne 'Portproxy update failed (add exit 1); the previous target 172.29.153.1:8877 was restored.') {
    throw 'A successful rollback must report that the previous target was restored.'
}
$restoreFailed = Get-Plan $Target $oldTarget $PreviousOwner @() 0 1 5
if ($restoreFailed -ne 'Portproxy update failed (add exit 1) and restoring the previous target 172.29.153.1:8877 also failed (exit 5).') {
    throw 'A failed rollback must report both add and restore failures.'
}
Write-Output 'Checking fresh creation'
if ((Get-Plan $Target $null $null) -ne 'Create|foreign-listeners=0') { throw 'A fresh install must create the narrow rule.' }

$rejectedUnowned = $false
try { Get-Plan $Target $sameTarget $null | Out-Null } catch { $rejectedUnowned = $true }
if (-not $rejectedUnowned) { throw 'An unowned same-listen mapping must fail closed.' }

$rejectedChangedMapping = $false
try { Get-Plan $Target $sameTarget ($Owner.Replace($Target, '172.29.153.9')) | Out-Null } catch { $rejectedChangedMapping = $true }
if (-not $rejectedChangedMapping) { throw 'A changed mapping must fail closed when it no longer matches its record.' }

Write-Output 'Portproxy ownership, repeat-start, and NAT-IP-update tests passed.'
