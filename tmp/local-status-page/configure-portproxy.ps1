param(
    [Parameter(Mandatory = $true)]
    [string] $ConnectAddress,

    [string] $ListenAddress = '192.168.50.100',

    [ValidateRange(1, 65535)]
    [int] $ListenPort = 8877,

    [ValidateRange(1, 65535)]
    [int] $ConnectPort = 8877,

    [switch] $WhatIf,

    [switch] $PlanOnly,

    [string] $TestExistingMapping,

    [string] $TestOwnerRecord,

    [int[]] $TestListenerPids = @(),

    [int] $TestIpHelperPid = 0,

    [int] $TestAddExitCode = -1,

    [int] $TestRestoreExitCode = -1
)

$ErrorActionPreference = 'Stop'
$OwnerName = 'FireMUD Local Status Page'
$RegistryPath = 'HKLM:\SOFTWARE\FireMUD\LocalStatusPage\PortProxy'

function Test-WslNatAddress([string] $Address) {
    return $Address -match '^172\.(1[6-9]|2[0-9]|3[01])\.'
}

function Get-PortProxyPlan($Existing, $Owner, [string] $DesiredAddress, [string] $DesiredPort,
    [string] $ExpectedListenAddress, [string] $ExpectedListenPort) {
    if ($Owner) {
        if (
            $Owner.Owner -ne 'FireMUD Local Status Page' -or [int] $Owner.Schema -ne 1 -or
            $Owner.ListenAddress -ne $ExpectedListenAddress -or
            [string] $Owner.ListenPort -ne $ExpectedListenPort -or
            [string] $Owner.ConnectPort -ne $DesiredPort -or
            -not (Test-WslNatAddress ([string] $Owner.ConnectAddress))
        ) {
            throw 'The saved portproxy ownership record is invalid; no forwarding rule was changed.'
        }
    }

    if ($Existing) {
        if (-not $Owner) {
            throw "A forwarding rule already uses ${ExpectedListenAddress}:${ExpectedListenPort}, but FireMUD has no ownership record; it was left unchanged."
        }
        if (
            $Existing.ListenAddress -ne $ExpectedListenAddress -or
            [string] $Existing.ListenPort -ne $ExpectedListenPort -or
            $Existing.ConnectAddress -ne $Owner.ConnectAddress -or
            [string] $Existing.ConnectPort -ne [string] $Owner.ConnectPort
        ) {
            throw "The forwarding rule at ${ExpectedListenAddress}:${ExpectedListenPort} no longer matches FireMUD's ownership record; it was left unchanged."
        }
        if ($Existing.ConnectAddress -eq $DesiredAddress) { return 'AlreadyCurrent' }
        return 'UpdateOwned'
    }

    if ($Owner) { return 'RestoreOwned' }
    return 'Create'
}

function Convert-MappingRow([string] $Row) {
    $fields = $Row.Trim() -split '\s+'
    if ($fields.Count -ne 4 -or $fields[1] -notmatch '^\d+$' -or $fields[3] -notmatch '^\d+$') { return $null }
    return [pscustomobject] @{
        ListenAddress = $fields[0]
        ListenPort = $fields[1]
        ConnectAddress = $fields[2]
        ConnectPort = $fields[3]
    }
}

function Read-OwnerRecord {
    if (-not (Test-Path -LiteralPath $RegistryPath)) { return $null }
    $registryItem = Get-ItemProperty -LiteralPath $RegistryPath -ErrorAction Stop
    if (-not $registryItem.Record) { throw 'The FireMUD portproxy ownership record is incomplete; no forwarding rule was changed.' }
    try { return ($registryItem.Record | ConvertFrom-Json -ErrorAction Stop) }
    catch { throw 'The FireMUD portproxy ownership record is invalid; no forwarding rule was changed.' }
}

function Write-OwnerRecord($Record) {
    New-Item -Path $RegistryPath -Force | Out-Null
    $serialized = $Record | ConvertTo-Json -Compress
    if (Get-ItemProperty -LiteralPath $RegistryPath -Name Record -ErrorAction SilentlyContinue) {
        Set-ItemProperty -LiteralPath $RegistryPath -Name Record -Value $serialized -ErrorAction Stop
    } else {
        New-ItemProperty -LiteralPath $RegistryPath -Name Record -Value $serialized -PropertyType String -Force -ErrorAction Stop | Out-Null
    }
}

function Restore-OwnerRecord($Record) {
    if ($Record) {
        Write-OwnerRecord $Record
    } elseif (Test-Path -LiteralPath $RegistryPath) {
        Remove-Item -LiteralPath $RegistryPath -Recurse -Force -ErrorAction Stop
    }
}

function Get-ForeignListeners([object[]] $Listeners, [int] $IpHelperPid) {
    if ($IpHelperPid -le 0) { return @($Listeners) }
    return @($Listeners | Where-Object { [int] $_.OwningProcess -ne $IpHelperPid })
}

function Format-UpdateRollbackFailure($Existing, [int] $AddExitCode, [int] $RestoreExitCode) {
    if ($RestoreExitCode -ne 0) {
        return "Portproxy update failed (add exit $AddExitCode) and restoring the previous target $($Existing.ConnectAddress):$($Existing.ConnectPort) also failed (exit $RestoreExitCode)."
    }
    return "Portproxy update failed (add exit $AddExitCode); the previous target $($Existing.ConnectAddress):$($Existing.ConnectPort) was restored."
}

foreach ($address in @($ListenAddress, $ConnectAddress)) {
    $parsed = $null
    if (
        -not [System.Net.IPAddress]::TryParse($address, [ref] $parsed) -or
        $parsed.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork
    ) {
        throw "Expected an IPv4 address; got '$address'."
    }
}
if (-not (Test-WslNatAddress $ConnectAddress)) {
    throw "The target '$ConnectAddress' is outside the current WSL NAT address range."
}

if ($PlanOnly) {
    $existingMapping = if ($TestExistingMapping) { $TestExistingMapping | ConvertFrom-Json } else { $null }
    $ownerRecord = if ($TestOwnerRecord) { $TestOwnerRecord | ConvertFrom-Json } else { $null }
    $plan = Get-PortProxyPlan $existingMapping $ownerRecord $ConnectAddress ([string] $ConnectPort) $ListenAddress ([string] $ListenPort)
    if ($TestAddExitCode -ge 0) {
        if ($plan -ne 'UpdateOwned') { throw 'Rollback simulation requires an owned target update.' }
        Write-Output (Format-UpdateRollbackFailure $existingMapping $TestAddExitCode $TestRestoreExitCode)
        return
    }
    $testListeners = @($TestListenerPids | ForEach-Object { [pscustomobject]@{ OwningProcess = $_ } })
    $foreign = @(Get-ForeignListeners $testListeners $TestIpHelperPid)
    Write-Output "$plan|foreign-listeners=$($foreign.Count)"
    return
}

$deleteArgs = @('interface', 'portproxy', 'delete', 'v4tov4', "listenaddress=$ListenAddress", "listenport=$ListenPort")
$addArgs = @('interface', 'portproxy', 'add', 'v4tov4', "listenaddress=$ListenAddress", "listenport=$ListenPort", "connectaddress=$ConnectAddress", "connectport=$ConnectPort")
if ($WhatIf) {
    Write-Output ("Would inspect and replace only the owned mapping ${ListenAddress}:${ListenPort}; add command: netsh.exe " + ($addArgs -join ' '))
    return
}

$netsh = Join-Path $env:WINDIR 'System32\netsh.exe'
$owner = Read-OwnerRecord
$mappingRows = @(& $netsh interface portproxy show v4tov4 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the existing IPv4 portproxy table.' }
$mappings = @($mappingRows | ForEach-Object { Convert-MappingRow ([string] $_) } | Where-Object { $_ })
$existing = $mappings | Where-Object { $_.ListenAddress -eq $ListenAddress -and $_.ListenPort -eq [string] $ListenPort } | Select-Object -First 1
$wildcardConflict = $mappings | Where-Object { $_.ListenAddress -eq '0.0.0.0' -and $_.ListenPort -eq [string] $ListenPort } | Select-Object -First 1
if ($wildcardConflict) {
    throw "A wildcard IPv4 forward already uses port $ListenPort; it was left unchanged."
}

$service = Get-CimInstance Win32_Service -Filter "Name = 'iphlpsvc'" -ErrorAction SilentlyContinue
$listeners = @(Get-NetTCPConnection -LocalAddress $ListenAddress -LocalPort $ListenPort -State Listen -ErrorAction SilentlyContinue)
$ipHelperPid = if ($service) { [int] $service.ProcessId } else { 0 }
$foreignListeners = @(Get-ForeignListeners $listeners $ipHelperPid)
if ($foreignListeners.Count -gt 0) {
    throw "${ListenAddress}:${ListenPort} is already owned by another process; no forwarding rule was changed."
}
if (-not $existing -and $listeners.Count -gt 0 -and -not $owner) {
    throw "${ListenAddress}:${ListenPort} has a listener without a matching owned forward; it was left unchanged."
}

$plan = Get-PortProxyPlan $existing $owner $ConnectAddress ([string] $ConnectPort) $ListenAddress ([string] $ListenPort)
if ($plan -eq 'AlreadyCurrent') {
    Write-Output "Portproxy already targets ${ConnectAddress}:${ConnectPort}; no change needed."
    return
}

if ($plan -eq 'UpdateOwned') {
    & $netsh @deleteArgs
    if ($LASTEXITCODE -ne 0) { throw 'Could not remove the owned FireMUD WSL portproxy rule; it was left unchanged.' }
}
& $netsh @addArgs
if ($LASTEXITCODE -ne 0) {
    $addExitCode = $LASTEXITCODE
    if ($plan -eq 'UpdateOwned') {
        $restoreArgs = @('interface', 'portproxy', 'add', 'v4tov4', "listenaddress=$ListenAddress", "listenport=$ListenPort", "connectaddress=$($existing.ConnectAddress)", "connectport=$($existing.ConnectPort)")
        & $netsh @restoreArgs
        $restoreExitCode = $LASTEXITCODE
        throw (Format-UpdateRollbackFailure $existing $addExitCode $restoreExitCode)
    }
    throw 'Could not add the FireMUD WSL portproxy rule.'
}

try {
    $newOwner = [pscustomobject]@{
        Owner = $OwnerName
        Schema = 1
        ListenAddress = $ListenAddress
        ListenPort = $ListenPort
        ConnectAddress = $ConnectAddress
        ConnectPort = $ConnectPort
    }
    Write-OwnerRecord $newOwner
} catch {
    $markerError = $_.Exception.Message
    & $netsh @deleteArgs
    $deleteExitCode = $LASTEXITCODE
    $restoreExitCode = 0
    if ($plan -eq 'UpdateOwned') {
        $restoreArgs = @('interface', 'portproxy', 'add', 'v4tov4', "listenaddress=$ListenAddress", "listenport=$ListenPort", "connectaddress=$($existing.ConnectAddress)", "connectport=$($existing.ConnectPort)")
        & $netsh @restoreArgs
        $restoreExitCode = $LASTEXITCODE
    }
    $markerRestoreError = $null
    try { Restore-OwnerRecord $owner } catch { $markerRestoreError = $_.Exception.Message }
    if ($deleteExitCode -ne 0 -or $restoreExitCode -ne 0 -or $markerRestoreError) {
        throw "Could not save the portproxy ownership record ($markerError); rollback also failed (delete exit $deleteExitCode, forward restore exit $restoreExitCode, marker restore: $markerRestoreError)."
    }
    throw "Could not save the portproxy ownership record ($markerError); the previous forwarding state was restored."
}

Write-Output "Portproxy target: ${ListenAddress}:${ListenPort} -> ${ConnectAddress}:${ConnectPort}"
