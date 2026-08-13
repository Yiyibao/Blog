param(
    [Parameter(Mandatory = $true)][string]$DatabaseUrl,
    [Parameter(Mandatory = $true)][string]$Username,
    [Parameter(Mandatory = $true)][SecureString]$Password,
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [ValidateRange(1, 999)][int]$ExpectedTargetVersion = 59,
    [ValidateRange(1, 168)][int]$MaxBackupAgeHours = 26,
    [string]$ExpectedBackupSha256,
    [ValidateRange(1048576, [long]::MaxValue)][long]$MinimumFreeBytes = 1073741824,
    [string]$CompatibilityFile = (Join-Path $PSScriptRoot '..\deploy\release-compatibility.env')
)

$ErrorActionPreference = 'Stop'
$resolvedBackup = [System.IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath $resolvedBackup -PathType Leaf)) { throw 'A completed backup file is required' }
$backupInfo = Get-Item -LiteralPath $resolvedBackup
if ($backupInfo.Length -le 0) { throw 'The backup file is empty' }
if ($backupInfo.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddHours(-$MaxBackupAgeHours)) {
    throw "The backup is older than the $MaxBackupAgeHours hour release window"
}
if ($ExpectedBackupSha256) {
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedBackup).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $ExpectedBackupSha256.Trim().ToLowerInvariant()) {
        throw 'The backup SHA-256 checksum does not match the reviewed value'
    }
}
$backupDrive = Get-PSDrive -Name ([System.IO.Path]::GetPathRoot($resolvedBackup).TrimEnd(':', '\'))
if ($backupDrive.Free -lt $MinimumFreeBytes) {
    throw "Insufficient free disk space: $($backupDrive.Free) bytes available"
}
$resolvedCompatibility = (Resolve-Path $CompatibilityFile).Path
$compatibility = @{}
Get-Content -LiteralPath $resolvedCompatibility | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $compatibility[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($compatibility['SCHEMA_TARGET'] -ne [string]$ExpectedTargetVersion) {
    throw 'Expected target version differs from the reviewed release compatibility contract'
}
if ($compatibility['MIGRATION_MODE'] -ne 'expand-only') {
    throw 'Release compatibility contract does not allow a code rollback window'
}
$rollbackFloor = 0
if (-not [int]::TryParse($compatibility['ROLLBACK_APP_MIN_SCHEMA'], [ref]$rollbackFloor)) {
    throw 'Release compatibility contract has an invalid rollback schema floor'
}
$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) { $psql = Get-Item 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -ErrorAction Stop }
$plain = [System.Net.NetworkCredential]::new('', $Password).Password
try {
    $env:PGPASSWORD = $plain
    $uri = $DatabaseUrl -replace '^jdbc:', ''
    $currentVersion = (& $psql.Source $uri --username $Username --set ON_ERROR_STOP=1 --tuples-only --no-align --command "select coalesce((select version from flyway_schema_history where success order by installed_rank desc limit 1), '0')").Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read Flyway version' }
    if ($currentVersion -notmatch '^\d+$') { throw "Flyway version is not numeric: $currentVersion" }
    if ([int]$currentVersion -gt $ExpectedTargetVersion) {
        throw "Database Flyway version V$currentVersion is newer than the reviewed target V$ExpectedTargetVersion"
    }
    if ([int]$currentVersion -lt $rollbackFloor) {
        throw "Database Flyway version V$currentVersion is below the reviewed rollback window V$rollbackFloor"
    }
    $failedMigrations = (& $psql.Source $uri --username $Username --set ON_ERROR_STOP=1 --tuples-only --no-align --command "select count(*) from flyway_schema_history where success = false").Trim()
    if ($LASTEXITCODE -ne 0 -or $failedMigrations -ne '0') { throw 'Flyway history contains failed migrations' }
    $tables = @(& $psql.Source $uri --username $Username --set ON_ERROR_STOP=1 --tuples-only --no-align --command "select relname from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r' and relname in ('posts','dishes','ai_files','ai_artifacts','ai_memories','ai_task_events') order by relname") |
        ForEach-Object { $_.Trim() } | Where-Object { $_ }
    if ($LASTEXITCODE -ne 0 -or 'posts' -notin $tables) { throw 'Production-like content checks failed: posts table is unavailable' }
    $checks = @('select count(*) as posts from posts')
    if ('dishes' -in $tables) { $checks += 'select count(*) as dishes from dishes' }
    foreach ($table in @('ai_files', 'ai_artifacts', 'ai_memories', 'ai_task_events')) {
        if ($table -in $tables) { $checks += "select count(*) as $table from $table" }
    }
    & $psql.Source $uri --username $Username --set ON_ERROR_STOP=1 --command ($checks -join '; ')
    if ($LASTEXITCODE -ne 0) { throw 'Production-like content checks failed' }
    Write-Host "Migration preflight passed; Flyway V$currentVersion -> V$ExpectedTargetVersion; backup confirmed at $resolvedBackup"
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $plain = $null
}
