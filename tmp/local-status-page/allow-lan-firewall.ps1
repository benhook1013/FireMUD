param(
    [switch] $WhatIf,

    [switch] $PlanOnly,

    [string] $TestFixtureJson
)

$ErrorActionPreference = 'Stop'

$name = 'FireMUD Local Status Page TCP 8877'
$expected = [ordered]@{
    Direction = 'Inbound'
    Action = 'Allow'
    Enabled = 'True'
    Profile = 'Private'
    Protocol = 'TCP'
    LocalPort = '8877'
    RemoteAddress = 'LocalSubnet'
    InterfaceAlias = 'WiFi'
}

function Test-ExactRuleShape($Rule, [object[]] $portFilters, [object[]] $addressFilters, [object[]] $interfaceFilters) {
    if ($portFilters.Count -ne 1 -or $addressFilters.Count -ne 1 -or $interfaceFilters.Count -ne 1) { return $false }
    $localPorts = @($portFilters[0].LocalPort | ForEach-Object { [string] $_ })
    $remoteAddresses = @($addressFilters[0].RemoteAddress | ForEach-Object { [string] $_ })
    $interfaces = @($interfaceFilters[0].InterfaceAlias | ForEach-Object { [string] $_ })
    return (
        $Rule.Direction -eq $expected.Direction -and $Rule.Action -eq $expected.Action -and
        [string] $Rule.Enabled -eq $expected.Enabled -and [string] $Rule.Profile -eq $expected.Profile -and
        [string] $portFilters[0].Protocol -eq $expected.Protocol -and
        $localPorts.Count -eq 1 -and $localPorts[0] -eq $expected.LocalPort -and
        $remoteAddresses.Count -eq 1 -and $remoteAddresses[0] -eq $expected.RemoteAddress -and
        $interfaces.Count -eq 1 -and $interfaces[0] -eq $expected.InterfaceAlias
    )
}

function Test-ExactRule($Rule) {
    $portFilters = @(Get-NetFirewallPortFilter -AssociatedNetFirewallRule $Rule -ErrorAction Stop)
    $addressFilters = @(Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $Rule -ErrorAction Stop)
    $interfaceFilters = @(Get-NetFirewallInterfaceFilter -AssociatedNetFirewallRule $Rule -ErrorAction Stop)
    return Test-ExactRuleShape $Rule $portFilters $addressFilters $interfaceFilters
}

if ($PlanOnly) {
    if (-not $TestFixtureJson) { throw 'PlanOnly requires a firewall-rule fixture.' }
    $fixture = $TestFixtureJson | ConvertFrom-Json -ErrorAction Stop
    $matches = Test-ExactRuleShape $fixture.Rule @($fixture.PortFilters) @($fixture.AddressFilters) @($fixture.InterfaceFilters)
    Write-Output ([string] $matches).ToLowerInvariant()
    return
}

$firewallService = Get-Service -Name MpsSvc -ErrorAction Stop
if ($firewallService.Status -ne 'Running') { throw 'Windows Defender Firewall is not running; no port forward was configured.' }
$privateProfile = @(Get-NetFirewallProfile -Profile Private -ErrorAction Stop)
if ($privateProfile.Count -ne 1 -or -not $privateProfile[0].Enabled) {
    throw 'The Private firewall profile is unavailable or disabled; no port forward was configured.'
}

$existing = @(Get-NetFirewallRule -DisplayName $name -ErrorAction Stop)
if ($existing.Count -gt 1) { throw "Multiple firewall rules use the FireMUD display name; inspect them before proceeding: $name" }
if ($existing.Count -eq 1) {
    if (-not (Test-ExactRule $existing[0])) { throw "Existing firewall rule '$name' does not match the narrow private Wi-Fi rule; it was left unchanged." }
    Write-Output "Verified existing inbound TCP 8877 rule: Private profile, Wi-Fi interface, LocalSubnet only."
    exit 0
}

if ($WhatIf) {
    Write-Output 'Would add inbound TCP 8877 on Wi-Fi, Private profile, LocalSubnet only.'
    return
}

$created = New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Enabled True `
    -Protocol TCP -LocalPort 8877 -Profile Private -RemoteAddress LocalSubnet -InterfaceAlias WiFi
if (-not $created -or -not (Test-ExactRule $created)) {
    if ($created) { Remove-NetFirewallRule -Name $created.Name -ErrorAction SilentlyContinue }
    throw 'The FireMUD firewall rule could not be verified; no broader fallback was applied.'
}

Write-Output 'Added and verified inbound TCP 8877 on Wi-Fi, Private profile, LocalSubnet only.'
