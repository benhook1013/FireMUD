<#
.SYNOPSIS
  Syncs the current repository into a WSL checkout (and optionally back again).

.DESCRIPTION
  Copies files between the Windows working tree and a mirror inside WSL so the
  Gradle build can run on a native Linux filesystem. By default the script
  copies from Windows -> WSL at ~/firemud-wsl. Pass -Reverse to copy from the
  WSL mirror back to Windows. Use -NoDelete to skip rsync's --delete flag.

.EXAMPLE
  ./dev-tools/sync-to-wsl.ps1
  Copies the repo into ~/firemud-wsl (creating it if needed).

.EXAMPLE
  ./dev-tools/sync-to-wsl.ps1 -Reverse
  Copies changes from the WSL mirror back into the Windows working tree.
#>
[CmdletBinding()]
param(
    [Parameter(Position=0)]
    [string]$Target = "~/firemud-wsl",
    [string]$Subdir = "",
    [switch]$Reverse,
    [switch]$NoDelete
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Convert-ToWslPath([string]$WindowsPath) {
    $escaped = $WindowsPath -replace '\\', '\\\\'
    $command = "wslpath -a '$escaped'"
    $output = & wsl bash -lc $command 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($output)) {
        throw "Failed to convert Windows path '$WindowsPath' to a WSL path."
    }
    return $output.Trim()
}

function Resolve-WslTarget([string]$TargetPath) {
    if ($TargetPath.StartsWith("~")) {
        $command = "mkdir -p $TargetPath && cd $TargetPath && pwd"
    } else {
        $escaped = $TargetPath -replace '\\', '\\\\'
        $command = "mkdir -p '$TargetPath' && realpath -m '$TargetPath'"
    }
    $output = & wsl bash -lc $command 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($output)) {
        throw "Failed to resolve WSL target path '$TargetPath'."
    }
    return $output.Trim()
}

if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) {
    throw "The 'wsl' command was not found. Install WSL and ensure it's on PATH."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$wslRepoPath = Convert-ToWslPath $repoRoot
$targetPath = Resolve-WslTarget $Target

if ($Reverse) {
    $sourcePath = $targetPath
    $destinationPath = $wslRepoPath
} else {
    $sourcePath = $wslRepoPath
    $destinationPath = $targetPath
}

if (-not [string]::IsNullOrWhiteSpace($Subdir)) {
    $sourcePath = "$sourcePath/$Subdir"
    $destinationPath = "$destinationPath/$Subdir"
}

$deleteFlag = if ($NoDelete) { "" } else { "--delete" }
$excludes = @("--exclude=node_modules","--exclude=node_modules/**","--exclude=build","--exclude=.gradle","--exclude=services/*/bin")
$rsyncCommand = @(
    "set -euo pipefail",
    "mkdir -p '$destinationPath'",
    "rsync -az --info=progress2 $deleteFlag $($excludes -join ' ') '$sourcePath/' '$destinationPath/'"
) -join " && "

Write-Host "Syncing from '$sourcePath' to '$destinationPath'..."
& wsl -e bash -lc "$rsyncCommand"
if ($LASTEXITCODE -ne 0) {
    throw "WSL rsync command failed with exit code $LASTEXITCODE."
}
Write-Host "Sync complete."
