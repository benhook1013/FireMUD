param(
    [ValidateRange(1, 65535)]
    [int] $Port = 8877,

    [string] $ListenAddress = '192.168.50.100'
)

$ErrorActionPreference = 'Stop'

$Distro = 'Ubuntu-22.04'
$Wsl = Join-Path $env:WINDIR 'System32\wsl.exe'
$ServerPath = '/home/ben/src/FireMUD-project-direction/tmp/local-status-page/server.py'
$ExternalOrigin = "http://${ListenAddress}:${Port}"
$LogDirectory = Join-Path $env:LOCALAPPDATA 'FireMUD\local-status-page'
$LogFile = Join-Path $LogDirectory 'wsl-server.log'
$FirewallScript = Join-Path $PSScriptRoot 'allow-lan-firewall.ps1'
$PortProxyScript = Join-Path $PSScriptRoot 'configure-portproxy.ps1'

New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
Add-Content -LiteralPath $LogFile -Value "$(Get-Date -Format o) Starting WSL status server for $ExternalOrigin"

try {
    $network = & $Wsl -d $Distro --exec /sbin/ip -4 -o address show dev eth0 scope global 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not read the WSL eth0 address: $($network -join ' ')" }
    $match = [regex]::Match(($network -join ' '), '\binet\s+((?:\d{1,3}\.){3}\d{1,3})/\d+')
    if (-not $match.Success) { throw "No IPv4 address was found on WSL eth0: $($network -join ' ')" }
    $WslAddress = $match.Groups[1].Value

    & $FirewallScript
    & $PortProxyScript -ListenAddress $ListenAddress -ListenPort $Port `
        -ConnectAddress $WslAddress -ConnectPort $Port

    Add-Content -LiteralPath $LogFile -Value "$(Get-Date -Format o) Serving WSL address $WslAddress`:$Port"
    # Windows PowerShell 5.1 turns redirected native stderr into an error record.
    # Python's routine HTTP access logs must not stop this long-running task.
    $priorErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Wsl -d $Distro --exec python3 $ServerPath --bind $WslAddress --port $Port --external-origin $ExternalOrigin *>> $LogFile
        $serverExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $priorErrorActionPreference
    }
    throw "The WSL status server exited with code $serverExitCode."
} catch {
    Add-Content -LiteralPath $LogFile -Value "$(Get-Date -Format o) Startup failed: $($_.Exception.Message)"
    throw
}
