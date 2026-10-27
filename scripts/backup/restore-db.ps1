[CmdletBinding()]
param(
    [string]$DumpFile,
    [string]$TargetDatabase = 'AnimeQuiz_restore',
    [string]$BackupRoot,
    [switch]$Force,
    [switch]$KeepExisting,
    [switch]$SkipCountCheck
)

. (Join-Path $PSScriptRoot '_common.ps1')

if ($BackupRoot) {
    $script:BackupRoot = $BackupRoot
    $script:DbDir      = Join-Path $BackupRoot 'db'
    $script:StorageDir = Join-Path $BackupRoot 'storage'
    $script:LogDir     = Join-Path $BackupRoot 'logs'
}

Initialize-BackupDirectories
$logFile = Join-Path $script:LogDir ('restore-db-' + (Get-Date -Format 'yyyy-MM') + '.log')

if ($TargetDatabase -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
    throw "Target database name must contain only letters, digits and underscores: $TargetDatabase"
}

if (-not $DumpFile) {
    $DumpFile = Get-NewestFile -Path $script:DbDir -Filter '*.dump'
}

if (-not $DumpFile) {
    throw "No dump file found in $($script:DbDir). Pass -DumpFile explicitly."
}

if (-not (Test-Path -LiteralPath $DumpFile)) {
    throw "Dump file not found: $DumpFile"
}

$live = ($TargetDatabase -ceq $script:PgDatabase)

if ($live -and -not $Force) {
    throw ("Refusing to overwrite the live database '{0}'. Stop the application and re-run with -Force, or restore into '{0}_restore' first." -f $TargetDatabase)
}

$exitCode = 0

try {
    Set-PgEnvironment
    $psql      = Resolve-PgTool -Name 'psql'
    $pgRestore = Resolve-PgTool -Name 'pg_restore'

    Write-Log ('Dump: ' + $DumpFile) $logFile
    Write-Log ('Size: {0:N1} MB' -f ((Get-Item -LiteralPath $DumpFile).Length / 1MB)) $logFile
    Write-Log ("Target: {0}:{1}/{2}" -f $script:PgHost, $script:PgPort, $TargetDatabase) $logFile

    $listCode = Invoke-Tool -FilePath $pgRestore -Arguments @('--list', $DumpFile) -LogFile $logFile -Quiet
    if ($listCode -ne 0) { throw "Dump is not readable by pg_restore (exit code $listCode)." }

    $hashFile = $DumpFile + '.sha256'
    if (Test-Path -LiteralPath $hashFile) {
        $expected = ((Get-Content -LiteralPath $hashFile -TotalCount 1) -split '\s+')[0]
        $actual = (Get-FileHash -LiteralPath $DumpFile -Algorithm SHA256).Hash
        if ($expected -and ($expected -ne $actual)) {
            throw "SHA256 mismatch. Expected $expected, got $actual. The dump file is corrupted."
        }
        Write-Log 'SHA256 checksum verified.' $logFile
    }

    if (Test-AppListening) {
        if ($live -and -not $Force) {
            throw ("The application is listening on port {0}. Stop it before restoring the live database." -f $script:AppPort)
        }
        Write-Log ("WARNING: something is listening on port {0}. For a live restore the application must be stopped." -f $script:AppPort) $logFile 'WARN'
    }

    $createdb = Resolve-PgTool -Name 'createdb'
    $dropdb   = Resolve-PgTool -Name 'dropdb'

    $existsQuery = "SELECT 1 FROM pg_database WHERE datname = '$TargetDatabase';"
    $existsLines = @(& $psql -h $script:PgHost -p $script:PgPort -U $script:PgUser -d postgres -w -t -A -v ON_ERROR_STOP=1 -c $existsQuery 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw ('Could not query pg_database: ' + (($existsLines | ForEach-Object { [string]$_ }) -join ' '))
    }
    $exists = (($existsLines | ForEach-Object { ([string]$_).Trim() }) -join '') -eq '1'

    if ($exists -and $KeepExisting) {
        Write-Log "Target database exists and -KeepExisting was set. Restoring on top with --clean." $logFile 'WARN'
    } else {
        if ($exists) {
            Write-Log "Dropping database '$TargetDatabase' (terminating connections)..." $logFile
            $dropCode = Invoke-Tool -FilePath $dropdb -Arguments @(
                '-h', $script:PgHost,
                '-p', [string]$script:PgPort,
                '-U', $script:PgUser,
                '-w',
                '--if-exists',
                '--force',
                $TargetDatabase
            ) -LogFile $logFile
            if ($dropCode -ne 0) { throw "dropdb failed with exit code $dropCode" }
        }

        Write-Log "Creating database '$TargetDatabase'..." $logFile
        $createCode = Invoke-Tool -FilePath $createdb -Arguments @(
            '-h', $script:PgHost,
            '-p', [string]$script:PgPort,
            '-U', $script:PgUser,
            '-w',
            '--encoding=UTF8',
            '--template=template0',
            $TargetDatabase
        ) -LogFile $logFile
        if ($createCode -ne 0) { throw "createdb failed with exit code $createCode" }
    }

    $restoreArgs = @(
        '-h', $script:PgHost,
        '-p', [string]$script:PgPort,
        '-U', $script:PgUser,
        '-d', $TargetDatabase,
        '--no-owner',
        '--no-privileges',
        '--no-password',
        '--exit-on-error',
        '--single-transaction'
    )
    if ($KeepExisting) {
        $restoreArgs += '--clean'
        $restoreArgs += '--if-exists'
    }
    $restoreArgs += $DumpFile

    Write-Log 'Restoring...' $logFile
    $code = Invoke-Tool -FilePath $pgRestore -Arguments $restoreArgs -LogFile $logFile
    if ($code -ne 0) { throw "pg_restore failed with exit code $code" }

    Write-Log 'Restore finished.' $logFile

    if (-not $SkipCountCheck) {
        Write-Log 'Row counts in the restored database:' $logFile
        foreach ($table in @('main.users', 'main.anime', 'main.user_anime', 'main.import_job')) {
            try {
                $null = Invoke-DbQuery -Psql $psql -Database $TargetDatabase -Query ("SELECT '{0}=' || count(*) FROM {1};" -f $table, $table) -LogFile $logFile
            } catch {
                Write-Log ("  Could not count {0}: {1}" -f $table, $_.Exception.Message) $logFile 'WARN'
            }
        }

        Write-Log 'Flyway history:' $logFile
        try {
            $null = Invoke-DbQuery -Psql $psql -Database $TargetDatabase -Query 'SELECT version || '' '' || description || '' success='' || success FROM main.flyway_schema_history ORDER BY installed_rank;' -LogFile $logFile
        } catch {
            Write-Log ('  Could not read flyway_schema_history: ' + $_.Exception.Message) $logFile 'WARN'
        }
    }

    if ($live) {
        Write-Log ("LIVE database '{0}' was replaced. Start the application and verify it manually." -f $TargetDatabase) $logFile 'WARN'
    } else {
        Write-Log ("Test database '{0}' is ready. To use it temporarily:" -f $TargetDatabase) $logFile
        Write-Log ("  java -jar target\AnimeQuiz-*.jar --spring.datasource.url=jdbc:postgresql://{0}:{1}/{2}?currentSchema=main" -f $script:PgHost, $script:PgPort, $TargetDatabase) $logFile
        Write-Log 'To switch permanently, change spring.datasource.url in application.properties.' $logFile
    }
}
catch {
    Write-Log ('ERROR: ' + $_.Exception.Message) $logFile 'ERROR'
    $exitCode = 1
}

if ($exitCode -eq 0) {
    Write-Log 'Database restore finished successfully.' $logFile
} else {
    Write-Log 'Database restore FAILED.' $logFile 'ERROR'
}

exit $exitCode
