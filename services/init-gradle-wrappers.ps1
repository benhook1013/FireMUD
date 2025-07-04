# Initializes Gradle wrapper files for each service
# Run this script from the services directory
param(
    [string]$GradleVersion = "8.5"
)

$projects = Get-ChildItem -Directory
foreach ($project in $projects) {
    $buildFile = Join-Path $project.FullName 'build.gradle.kts'
    if (Test-Path $buildFile) {
        Write-Host "Initializing wrapper in $($project.Name)..."
        Push-Location $project.FullName
        gradle wrapper --gradle-version $GradleVersion --distribution-type bin
        Pop-Location
    }
}
