# Print a file with 3-digit line numbers for easy reference.
# Usage: print-lines-with-numbers.ps1 <path> [-StartLine <n>] [-Count <n>] [-EndLine <n>]
#   - `StartLine` skips lines before the provided number.
#   - `Count` limits how many lines are shown (0 = unlimited up to EndLine/default EOF).
#   - `EndLine` stops once the numbered line exceeds this value.
param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Path,
    [int]$StartLine = 1,
    [int]$Count = 0,
    [int]$EndLine = 0
)

if (-not (Test-Path $Path)) {
    Write-Error "File not found: $Path"
    exit 1
}

if ($Count -lt 0) {
    Write-Error "`$Count must be zero or positive."
    exit 1
}

if ($EndLine -gt 0 -and $EndLine -lt $StartLine) {
    Write-Error "`$EndLine must not be less than `$StartLine."
    exit 1
}

$printed = 0
$lineNumber = 0
Get-Content $Path | ForEach-Object {
    $lineNumber++

    if ($lineNumber -lt $StartLine) {
        return
    }

    if ($EndLine -gt 0 -and $lineNumber -gt $EndLine) {
        return
    }

    if ($Count -gt 0 -and $printed -ge $Count) {
        return
    }

    "{0:000}: {1}" -f $lineNumber, $_
    $printed++
}
