$ErrorActionPreference='Stop'
$projectDir=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath (Join-Path $projectDir 'frontend')
# Some IDE launchers inject a global-only npm option. Clear it for this process.
Remove-Item Env:npm_config_allow_scripts -ErrorAction SilentlyContinue
if (-not (Test-Path -LiteralPath 'node_modules')) {
    & npm.cmd ci --ignore-scripts --cache ../.npm-cache
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& npm.cmd run dev
exit $LASTEXITCODE
