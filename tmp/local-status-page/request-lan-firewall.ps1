$ErrorActionPreference = 'Stop'

$ruleScript = Join-Path $PSScriptRoot 'allow-lan-firewall.ps1'
$arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $ruleScript + '"'
$process = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -Verb RunAs -ArgumentList $arguments -Wait -PassThru
Write-Output "Elevated firewall command exited with code $($process.ExitCode)."
exit $process.ExitCode
