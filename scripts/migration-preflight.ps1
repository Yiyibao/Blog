param(
    [Parameter(Mandatory = $true)][string]$DatabaseUrl,
    [Parameter(Mandatory = $true)][string]$Username,
    [Parameter(Mandatory = $true)][SecureString]$Password,
    [Parameter(Mandatory = $true)][string]$BackupFile
)

$ErrorActionPreference = 'Stop'
$resolvedBackup = [System.IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath $resolvedBackup -PathType Leaf)) { throw 'A completed backup file is required' }
if ((Get-Item -LiteralPath $resolvedBackup).Length -le 0) { throw 'The backup file is empty' }
$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) { $psql = Get-Item 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -ErrorAction Stop }
$plain = [System.Net.NetworkCredential]::new('', $Password).Password
try {
    $env:PGPASSWORD = $plain
    $uri = $DatabaseUrl -replace '^jdbc:', ''
    & $psql.Source $uri --username $Username --set ON_ERROR_STOP=1 --tuples-only --no-align --command "select version from flyway_schema_history where success order by installed_rank desc limit 1"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read Flyway version' }
    & $psql.Source $uri --username $Username --set ON_ERROR_STOP=1 --command "select count(*) as posts from posts; select count(*) as dishes from dishes;"
    if ($LASTEXITCODE -ne 0) { throw 'Production-like content checks failed' }
    Write-Host "Migration preflight passed; backup confirmed at $resolvedBackup"
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $plain = $null
}
