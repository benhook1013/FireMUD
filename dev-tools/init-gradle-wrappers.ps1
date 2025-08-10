# Initializes Gradle wrapper files for each service
param(
    [string]$GradleVersion = "8.5"
)

$servicesDir = Resolve-Path "$PSScriptRoot/../services"
Get-ChildItem -Path $servicesDir -Directory | ForEach-Object {
    $buildFile = Join-Path $_.FullName 'build.gradle.kts'
    if (Test-Path $buildFile) {
        Write-Host "Initializing wrapper in $($_.Name)..."
        Push-Location $_.FullName
        gradle wrapper --gradle-version $GradleVersion --distribution-type bin
        Pop-Location
    }
}
