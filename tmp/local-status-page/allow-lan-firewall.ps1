$ErrorActionPreference = 'Stop'

$name = 'FireMUD Local Status Page TCP 8877'
$existing = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($existing) {
    throw "Firewall rule already exists; inspect it before changing: $name"
}

New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8877 -Profile Private -RemoteAddress LocalSubnet -InterfaceAlias WiFi | Out-Null
Write-Output "Added private Wi-Fi local-subnet rule for TCP 8877."
