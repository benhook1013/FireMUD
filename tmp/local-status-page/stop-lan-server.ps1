$ErrorActionPreference = 'Stop'

$Port = 8877
$PidFile = Join-Path $PSScriptRoot 'lan-server.pid'
$OutputDirectory = '\\wsl.localhost\Ubuntu-22.04\home\ben\src\FireMUD-project-direction\tmp\local-status-page\output'
$ServerScript = Join-Path $PSScriptRoot 'server.py'

if (-not (Test-Path -LiteralPath $PidFile)) {
    Write-Output "No managed local status page server is recorded."
    exit 0
}

$serverPid = [int](Get-Content -LiteralPath $PidFile -Raw)
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $serverPid" -ErrorAction SilentlyContinue
if ($process) {
    $commandLine = [string]$process.CommandLine
    $knownServer = ($commandLine -match 'http\.server\s+8877') -or ($commandLine -like "*$ServerScript*")
    if (-not $knownServer -or ($commandLine -notlike "*$OutputDirectory*")) {
        throw "PID $serverPid no longer identifies the managed status page server; it was left running."
    }
    Stop-Process -Id $serverPid -Force
}

Remove-Item -LiteralPath $PidFile -Force
Write-Output "Stopped the managed local status page server on TCP port $Port."
