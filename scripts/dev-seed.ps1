param(
    [Parameter(Mandatory = $true)][string]$DatabaseUrl,
    [Parameter(Mandatory = $true)][string]$Username,
    [Parameter(Mandatory = $true)][SecureString]$Password
)

$ErrorActionPreference = 'Stop'
if ($DatabaseUrl -notmatch 'localhost|127\.0\.0\.1') {
    throw 'dev-seed refuses non-local database URLs'
}
$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) { $psql = Get-Item 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -ErrorAction Stop }
$plain = [System.Net.NetworkCredential]::new('', $Password).Password
try {
    $env:PGPASSWORD = $plain
    $uri = $DatabaseUrl -replace '^jdbc:', ''
    & $psql.Source $uri --username $Username --set ON_ERROR_STOP=1 --file "$PSScriptRoot\..\backend\database\dev-seed.sql"
    if ($LASTEXITCODE -ne 0) { throw "dev-seed failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $plain = $null
}
