[CmdletBinding()]
param(
    [string]$BackupRoot,
    [int]$KeepDays = 30,
    [switch]$SkipVerify
)

. (Join-Path $PSScriptRoot '_common.ps1')

if ($BackupRoot) {
    $script:BackupRoot = $BackupRoot
    $script:DbDir      = Join-Path $BackupRoot 'db'
    $script:StorageDir = Join-Path $BackupRoot 'storage'
    $script:LogDir     = Join-Path $BackupRoot 'logs'
}

Initialize-BackupDirectories
$logFile = Join-Path $script:LogDir ('backup-db-' + (Get-Date -Format 'yyyy-MM') + '.log')
$partial = $null

$lock = Enter-BackupLock -Name 'db'
if (-not $lock) {
    Write-Log 'Another database backup is already running. Skipping this run.' $logFile 'WARN'
    exit 0
}

$exitCode = 0

try {
    Set-PgEnvironment
    $pgDump    = Resolve-PgTool -Name 'pg_dump'
    $pgRestore = Resolve-PgTool -Name 'pg_restore'

    $freeGb = Test-FreeSpace -Path $script:BackupRoot -RequiredGb $script:MinFreeSpaceGb
    Write-Log ("Free space before backup: {0} GB" -f $freeGb) $logFile
    Write-Log ("Source: {0}:{1}/{2} (user {3})" -f $script:PgHost, $script:PgPort, $script:PgDatabase, $script:PgUser) $logFile
    Write-Log ('pg_dump: ' + $pgDump) $logFile

    $versionCode = Invoke-Tool -FilePath $pgDump -Arguments @('--version') -LogFile $logFile -Quiet
    if ($versionCode -ne 0) { throw "pg_dump --version failed with exit code $versionCode" }

    $stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
    $dumpFile = Join-Path $script:DbDir ('{0}_{1}.dump' -f $script:PgDatabase, $stamp)
    $partial = $dumpFile + '.part'

    $arguments = @(
        '-h', $script:PgHost,
        '-p', [string]$script:PgPort,
        '-U', $script:PgUser,
        '-d', $script:PgDatabase,
        '--format=custom',
        '--compress=9',
        '--no-owner',
        '--no-privileges',
        '--no-password',
        '--lock-wait-timeout=60s',
        '--encoding=UTF8',
        ('--file=' + $partial)
    )

    $code = Invoke-Tool -FilePath $pgDump -Arguments $arguments -LogFile $logFile
    if ($code -ne 0) { throw "pg_dump failed with exit code $code" }

    if (-not (Test-Path -LiteralPath $partial)) {
        throw 'pg_dump reported success but did not create a file.'
    }

    $size = (Get-Item -LiteralPath $partial).Length
    if ($size -lt 1024) {
        throw "Dump is suspiciously small: $size bytes."
    }

    if (-not $SkipVerify) {
        $listCode = Invoke-Tool -FilePath $pgRestore -Arguments @('--list', $partial) -LogFile $logFile -Quiet
        if ($listCode -ne 0) { throw "pg_restore --list failed with exit code $listCode" }
        Write-Log 'Dump verified with pg_restore --list.' $logFile
    }

    Move-Item -LiteralPath $partial -Destination $dumpFile -Force
    $partial = $null

    $hash = (Get-FileHash -LiteralPath $dumpFile -Algorithm SHA256).Hash
    $hashFile = $dumpFile + '.sha256'
    [System.IO.File]::WriteAllText($hashFile, ('{0}  {1}' -f $hash, [System.IO.Path]::GetFileName($dumpFile)) + [Environment]::NewLine, $script:Utf8NoBom)

    Write-Log ("BACKUP OK: {0} ({1:N1} MB)" -f $dumpFile, ($size / 1MB)) $logFile
    Write-Log ("SHA256: {0}" -f $hash) $logFile

    $freeAfter = Test-FreeSpace -Path $script:BackupRoot -RequiredGb 1
    Write-Log ("Free space after backup: {0} GB" -f $freeAfter) $logFile

    $limit = (Get-Date).AddDays(-$KeepDays)
    Get-ChildItem -LiteralPath $script:DbDir -Filter '*.dump' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $limit } |
        ForEach-Object {
            try {
                Remove-Item -LiteralPath $_.FullName -Force
                $side = $_.FullName + '.sha256'
                if (Test-Path -LiteralPath $side) { Remove-Item -LiteralPath $side -Force }
                Write-Log ("Deleted old dump: {0}" -f $_.Name) $logFile
            } catch {
                Write-Log ("Could not delete {0}: {1}" -f $_.Name, $_.Exception.Message) $logFile 'WARN'
            }
        }

    Get-ChildItem -LiteralPath $script:DbDir -Filter '*.part' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
        ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
            Write-Log ("Deleted stale partial file: {0}" -f $_.Name) $logFile 'WARN'
        }

    $count = @(Get-ChildItem -LiteralPath $script:DbDir -Filter '*.dump' -File -ErrorAction SilentlyContinue).Count
    Write-Log ("Stored database backups: {0} (retention {1} days)" -f $count, $KeepDays) $logFile
}
catch {
    Write-Log ('ERROR: ' + $_.Exception.Message) $logFile 'ERROR'
    if ($partial -and (Test-Path -LiteralPath $partial)) {
        Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
        Write-Log 'Removed incomplete dump file.' $logFile 'WARN'
    }
    $exitCode = 1
}
finally {
    Exit-BackupLock $lock
}

if ($exitCode -eq 0) {
    Write-Log 'Database backup finished.' $logFile
} else {
    Write-Log 'Database backup FAILED.' $logFile 'ERROR'
}

exit $exitCode
