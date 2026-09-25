$ErrorActionPreference = 'Stop'

$Port = 8877
$InterfaceAlias = 'WiFi'
$Python = 'C:\Users\Ben_Desktop\AppData\Local\Programs\Python\Python311\python.exe'
$ServerScript = Join-Path $PSScriptRoot 'server.py'
$OutputDirectory = '\\wsl.localhost\Ubuntu-22.04\home\ben\src\FireMUD-project-direction\tmp\local-status-page\output'
$PidFile = Join-Path $PSScriptRoot 'lan-server.pid'
$StdoutFile = Join-Path $PSScriptRoot 'lan-server.stdout.log'
$StderrFile = Join-Path $PSScriptRoot 'lan-server.stderr.log'

if (-not (Test-Path -LiteralPath $Python)) { throw "Windows Python was not found: $Python" }
if (-not (Test-Path -LiteralPath $ServerScript)) { throw "Status page server was not found: $ServerScript" }
if (-not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'index.html'))) { throw "Status page was not found: $OutputDirectory\index.html" }

$deadline = (Get-Date).AddSeconds(90)
$Address = $null
do {
    $Address = Get-NetIPAddress -InterfaceAlias $InterfaceAlias -AddressFamily IPv4 -AddressState Preferred -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -ne '127.0.0.1' -and -not $_.SkipAsSource } |
        Select-Object -First 1 -ExpandProperty IPAddress
    if (-not $Address) { Start-Sleep -Seconds 3 }
} while (-not $Address -and (Get-Date) -lt $deadline)
if (-not $Address) { throw "No preferred IPv4 address is available on interface '$InterfaceAlias'." }

$listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($listeners.Count -gt 0) {
    $recordedPid = if (Test-Path -LiteralPath $PidFile) { [int](Get-Content -LiteralPath $PidFile -Raw) } else { -1 }
    $managedListener = $listeners | Where-Object { $_.OwningProcess -eq $recordedPid } | Select-Object -First 1
    if ($managedListener -and $managedListener.LocalAddress -eq $Address) {
        $runningProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $recordedPid" -ErrorAction SilentlyContinue
        if (($runningProcess.CommandLine -like "*$ServerScript*") -and ($runningProcess.CommandLine -like "*$OutputDirectory*")) {
            Write-Output "Local status page already running at http://${Address}:${Port}/"
            exit 0
        }
    }
    if ($managedListener) {
        $managedProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $recordedPid" -ErrorAction SilentlyContinue
        $knownServer = ($managedProcess.CommandLine -match 'http\.server\s+8877') -or ($managedProcess.CommandLine -like "*$ServerScript*")
        if (-not $knownServer -or ($managedProcess.CommandLine -notlike "*$OutputDirectory*")) {
            throw "Recorded PID $recordedPid no longer identifies the managed status page server; it was left running."
        }
        Stop-Process -Id $recordedPid -Force
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 250
    } else {
        throw "TCP port $Port is already occupied; no process was changed."
    }
}

Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
$process = Start-Process -FilePath $Python `
    -ArgumentList @($ServerScript, '--port', "$Port", '--bind', $Address, '--directory', $OutputDirectory) `
    -WindowStyle Hidden -RedirectStandardOutput $StdoutFile -RedirectStandardError $StderrFile -PassThru
Set-Content -LiteralPath $PidFile -Value $process.Id -Encoding Ascii

$deadline = (Get-Date).AddSeconds(10)
do {
    Start-Sleep -Milliseconds 250
    $listener = Get-NetTCPConnection -LocalAddress $Address -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        $serverPid = [int]$listener.OwningProcess
        $serverProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $serverPid" -ErrorAction SilentlyContinue
        if (($serverProcess.CommandLine -notlike "*$ServerScript*") -or ($serverProcess.CommandLine -notlike "*$OutputDirectory*")) {
            throw "TCP port $Port was acquired by an unexpected process; it was left running."
        }
        Set-Content -LiteralPath $PidFile -Value $serverPid -Encoding Ascii
        Write-Output "Local status page running at http://${Address}:${Port}/ (PID $serverPid)"
        exit 0
    }
    if ($process.HasExited) { break }
} while ((Get-Date) -lt $deadline)

if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
throw "The status page server did not start. See $StderrFile"
