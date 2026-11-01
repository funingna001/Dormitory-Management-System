param([ValidateSet('sqlite','mysql')][string]$Database='mysql')
$ErrorActionPreference='Stop'
$projectDir=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectDir
$repoDir=Join-Path $projectDir '.maven-repository'
if ($Database -eq 'mysql') {
    if (-not $env:DB_PASSWORD -and -not (Test-Path -LiteralPath (Join-Path $projectDir '.env'))) {
        throw 'Set local MySQL credentials in the project .env file or DB_PASSWORD environment variable.'
    }
    $env:SPRING_PROFILES_ACTIVE='mysql'
} else {
    $env:SPRING_PROFILES_ACTIVE='sqlite'
}
if (Get-Command mvn -ErrorAction SilentlyContinue) {
    & mvn "-Dmaven.repo.local=$repoDir" spring-boot:run
} else {
    & (Join-Path $projectDir 'mvnw.cmd') "-Dmaven.repo.local=$repoDir" spring-boot:run
}
exit $LASTEXITCODE
