$ErrorActionPreference = 'Stop'

$Helper = Join-Path $PSScriptRoot 'allow-lan-firewall.ps1'
$fixture = [ordered]@{
    Rule = @{ Direction = 'Inbound'; Action = 'Allow'; Enabled = 'True'; Profile = 'Private' }
    PortFilters = @(@{ Protocol = 'TCP'; LocalPort = @('8877') })
    AddressFilters = @(@{ RemoteAddress = @('LocalSubnet') })
    InterfaceFilters = @(@{ InterfaceAlias = @('WiFi') })
}

function Test-Fixture($Value) {
    $json = $Value | ConvertTo-Json -Compress -Depth 8
    return (& $Helper -PlanOnly -TestFixtureJson $json | Out-String).Trim()
}

if ((Test-Fixture $fixture) -ne 'true') { throw 'The exact narrow firewall rule should be accepted.' }

$fixture.Rule.Profile = 'Any'
if ((Test-Fixture $fixture) -ne 'false') { throw 'A broad profile should be rejected.' }
$fixture.Rule.Profile = 'Private'

$fixture.AddressFilters[0].RemoteAddress = @('Any')
if ((Test-Fixture $fixture) -ne 'false') { throw 'A broad remote-address scope should be rejected.' }
$fixture.AddressFilters[0].RemoteAddress = @('LocalSubnet')

$fixture.InterfaceFilters[0].InterfaceAlias = @('Any')
if ((Test-Fixture $fixture) -ne 'false') { throw 'A broad interface scope should be rejected.' }
$fixture.InterfaceFilters[0].InterfaceAlias = @('WiFi')

$fixture.PortFilters[0].LocalPort = @('8877', '80')
if ((Test-Fixture $fixture) -ne 'false') { throw 'A rule covering extra ports should be rejected.' }

Write-Output 'Firewall rule exact-scope tests passed.'
