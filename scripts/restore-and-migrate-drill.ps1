param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath,
    [int]$Port = 55432
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendDir = Join-Path $repoRoot 'backend'
$resolvedBackup = (Resolve-Path $BackupPath).Path
$backupInfo = Get-Item -LiteralPath $resolvedBackup
if ($backupInfo.Length -le 0) {
    throw 'Backup file is empty.'
}

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$drillRoot = Join-Path $repoRoot "outputs\restore-drill\$runId"
$clusterDir = Join-Path $drillRoot 'postgres'
$storageDir = Join-Path $drillRoot 'attachments'
$postgresLog = Join-Path $drillRoot 'postgres.log'
$applicationOut = Join-Path $drillRoot 'application.out.log'
$applicationErr = Join-Path $drillRoot 'application.err.log'
$resultPath = Join-Path $drillRoot 'result.txt'
$queryAuditPath = Join-Path $drillRoot 'query-audit.log'
New-Item -ItemType Directory -Path $clusterDir, $storageDir -Force | Out-Null

$initdb = (Get-Command initdb -ErrorAction Stop).Source
$pgCtl = (Get-Command pg_ctl -ErrorAction Stop).Source
$postgres = (Get-Command postgres -ErrorAction Stop).Source
$createdb = (Get-Command createdb -ErrorAction Stop).Source
$pgRestore = (Get-Command pg_restore -ErrorAction Stop).Source
$psql = (Get-Command psql -ErrorAction Stop).Source
$pgIsReady = (Get-Command pg_isready -ErrorAction Stop).Source
$database = 'blog_restore_drill'
$jdbcUrl = "jdbc:postgresql://127.0.0.1:$Port/$database"
$serverStarted = $false
$databaseServer = $null
$application = $null

try {
    & $initdb -D $clusterDir -U postgres -A trust -E UTF8 --no-locale *> (Join-Path $drillRoot 'initdb.log')
    if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE" }

    $databaseServer = Start-Process -FilePath $postgres `
        -ArgumentList "-D `"$clusterDir`" -p $Port -h 127.0.0.1 -c shared_preload_libraries=pg_stat_statements" `
        -RedirectStandardOutput $postgresLog -RedirectStandardError (Join-Path $drillRoot 'postgres.err.log') `
        -WindowStyle Hidden -PassThru
    $serverStarted = $true

    $readyDeadline = (Get-Date).AddSeconds(30)
    $ready = $false
    while ((Get-Date) -lt $readyDeadline) {
        Start-Sleep -Milliseconds 250
        & $pgIsReady -h 127.0.0.1 -p $Port -U postgres -d postgres -q
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        $databaseServer.Refresh()
        if ($databaseServer.HasExited) { throw "PostgreSQL exited with code $($databaseServer.ExitCode)." }
    }
    if (-not $ready) { throw 'PostgreSQL did not become ready within 30 seconds.' }

    & $createdb -h 127.0.0.1 -p $Port -U postgres $database
    if ($LASTEXITCODE -ne 0) { throw "createdb failed with exit code $LASTEXITCODE" }

    & $pgRestore -h 127.0.0.1 -p $Port -U postgres -d $database --no-owner --no-acl --exit-on-error $resolvedBackup
    if ($LASTEXITCODE -ne 0) { throw "pg_restore failed with exit code $LASTEXITCODE" }

    $beforeVersion = (& $psql -h 127.0.0.1 -p $Port -U postgres -d $database -Atc `
        "select coalesce((select version from flyway_schema_history where success order by installed_rank desc limit 1), '0')").Trim()
    $beforePosts = (& $psql -h 127.0.0.1 -p $Port -U postgres -d $database -Atc 'select count(*) from posts').Trim()
    $beforeDishes = (& $psql -h 127.0.0.1 -p $Port -U postgres -d $database -Atc 'select count(*) from dishes').Trim()

    $jar = Get-ChildItem -Path (Join-Path $backendDir 'target') -Filter 'blog-backend-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw 'Backend executable jar was not found. Run Maven package first.' }

    $args = @(
        '-jar', $jar.FullName,
        "--spring.datasource.url=$jdbcUrl",
        '--spring.datasource.username=postgres',
        '--spring.datasource.password=restore-drill',
        '--server.port=0',
        '--app.jwt.secret=restore-drill-only-secret-that-is-long-enough-20260809',
        '--app.jwt.cookie-secure=false',
        '--app.site-url=http://localhost',
        '--app.cors.allowed-origins=http://localhost',
        "--app.attachment.storage.dir=$storageDir",
        '--app.admin.username=',
        '--app.partner.username=',
        '--app.ai.enabled=false',
        '--app.recipe.extraction.video-enabled=false'
    )
    $application = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $backendDir `
        -RedirectStandardOutput $applicationOut -RedirectStandardError $applicationErr `
        -WindowStyle Hidden -PassThru

    $deadline = (Get-Date).AddSeconds(90)
    $afterVersion = '0'
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $application.Refresh()
        if ($application.HasExited) {
            throw "Application exited before migration verification (exit $($application.ExitCode)). See $applicationOut and $applicationErr"
        }
        $afterVersion = (& $psql -h 127.0.0.1 -p $Port -U postgres -d $database -Atc `
            "select coalesce((select version from flyway_schema_history where success order by installed_rank desc limit 1), '0')" 2>$null).Trim()
        if ([int]$afterVersion -ge 49) { break }
    }
    if ([int]$afterVersion -lt 49) { throw "Flyway did not reach V49 before timeout (current: $afterVersion)" }

    $afterPosts = (& $psql -h 127.0.0.1 -p $Port -U postgres -d $database -Atc 'select count(*) from posts').Trim()
    $afterDishes = (& $psql -h 127.0.0.1 -p $Port -U postgres -d $database -Atc 'select count(*) from dishes').Trim()
    $deletedAtExists = (& $psql -h 127.0.0.1 -p $Port -U postgres -d $database -Atc `
        "select count(*) from information_schema.columns where table_name='note_attachments' and column_name='deleted_at'").Trim()
    $budgetTableExists = (& $psql -h 127.0.0.1 -p $Port -U postgres -d $database -Atc `
        "select count(*) from information_schema.tables where table_name='ai_daily_budgets'").Trim()

    if ($beforePosts -ne $afterPosts -or $beforeDishes -ne $afterDishes) {
        throw "Content counts changed during migration: posts $beforePosts->$afterPosts, dishes $beforeDishes->$afterDishes"
    }
    if ($deletedAtExists -ne '1' -or $budgetTableExists -ne '1') {
        throw 'Expected V48/V49 schema objects were not found.'
    }

    & $psql -h 127.0.0.1 -p $Port -U postgres -d $database -v ON_ERROR_STOP=1 `
        -f (Join-Path $repoRoot 'scripts\query-audit.sql') *> $queryAuditPath
    if ($LASTEXITCODE -ne 0) { throw "Query audit failed with exit code $LASTEXITCODE" }

    @(
        'status=PASS',
        "backup=$resolvedBackup",
        "cluster=$clusterDir",
        "flyway=$beforeVersion->$afterVersion",
        "posts=$beforePosts->$afterPosts",
        "dishes=$beforeDishes->$afterDishes",
        "ai_daily_budgets=$budgetTableExists",
        "note_attachments.deleted_at=$deletedAtExists"
        "query_audit=$queryAuditPath"
    ) | Set-Content -LiteralPath $resultPath -Encoding utf8
    Get-Content -LiteralPath $resultPath
}
finally {
    if ($application -and -not $application.HasExited) {
        Stop-Process -Id $application.Id -Force -ErrorAction SilentlyContinue
        $application.WaitForExit(10000) | Out-Null
    }
    if ($serverStarted) {
        & $pgCtl -D $clusterDir -m fast -w stop
    }
}
