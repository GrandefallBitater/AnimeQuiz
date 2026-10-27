[CmdletBinding()]
param(
    [string]$ArchiveFile,
    [string]$TargetPath,
    [string]$BackupRoot,
    [switch]$Force,
    [switch]$RemoveOldOnSuccess,
    [switch]$SkipVerify
)

. (Join-Path $PSScriptRoot '_common.ps1')

if ($BackupRoot) {
    $script:BackupRoot = $BackupRoot
    $script:DbDir      = Join-Path $BackupRoot 'db'
    $script:StorageDir = Join-Path $BackupRoot 'storage'
    $script:LogDir     = Join-Path $BackupRoot 'logs'
}

if ($TargetPath) { $script:StoragePath = $TargetPath }

Initialize-BackupDirectories
$logFile = Join-Path $script:LogDir ('restore-storage-' + (Get-Date -Format 'yyyy-MM') + '.log')

if (-not $ArchiveFile) {
    $ArchiveFile = Get-NewestFile -Path $script:StorageDir -Filter 'storage_*.zip'
}

if (-not $ArchiveFile) {
    throw "No storage archive found in $($script:StorageDir). Pass -ArchiveFile explicitly."
}

if (-not (Test-Path -LiteralPath $ArchiveFile)) {
    throw "Archive not found: $ArchiveFile"
}

$exitCode = 0
$oldPath = $null
$backupPath = $null

try {
    $tar = Join-Path $env:SystemRoot 'System32\tar.exe'
    if (-not (Test-Path -LiteralPath $tar)) {
        $cmd = Get-Command tar.exe -ErrorAction SilentlyContinue
        if (-not $cmd) { throw 'tar.exe not found. Windows 10 1803+ or newer is required.' }
        $tar = $cmd.Source
    }

    Write-Log ('Archive: ' + $ArchiveFile) $logFile
    Write-Log ('Size: {0:N1} MB' -f ((Get-Item -LiteralPath $ArchiveFile).Length / 1MB)) $logFile
    Write-Log ('Target: ' + $script:StoragePath) $logFile

    if (Test-AppListening) {
        if (-not $Force) {
            throw ("The application is listening on port {0}. Stop it before restoring storage, or pass -Force." -f $script:AppPort)
        }
        Write-Log ("WARNING: something is listening on port {0}. The application should be stopped." -f $script:AppPort) $logFile 'WARN'
    }

    $listing = @()
    if (-not $SkipVerify) {
        Write-Log 'Checking archive readability...' $logFile
        $listing = @(& $tar -t -f $ArchiveFile 2>&1)
        $listCode = $LASTEXITCODE
        if ($listCode -ne 0) { throw "tar -t failed with exit code $listCode" }

        $archivedFiles = 0
        foreach ($entry in $listing) {
            $text = [string]$entry
            if ($text -and -not $text.EndsWith('/') -and -not $text.EndsWith('\')) {
                $archivedFiles++
            }
        }
        Write-Log ("Archive contains {0} files" -f $archivedFiles) $logFile
        if ($archivedFiles -eq 0) { throw 'Archive contains no files.' }
    }

    if (Test-Path -LiteralPath $script:StoragePath) {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $backupPath = $script:StoragePath + '.bak-' + $stamp
        Write-Log ("Moving current storage to {0}" -f $backupPath) $logFile
        Move-Item -LiteralPath $script:StoragePath -Destination $backupPath -Force
        $oldPath = $script:StoragePath
    }

    if (-not (Test-Path -LiteralPath $script:StoragePath)) {
        New-Item -ItemType Directory -Path $script:StoragePath -Force | Out-Null
    }

    Write-Log 'Extracting archive...' $logFile
    $code = Invoke-Tool -FilePath $tar -Arguments @('-x', '-f', $ArchiveFile, '-C', $script:StoragePath) -LogFile $logFile -Quiet
    if ($code -ne 0) { throw "tar extraction failed with exit code $code" }

    if (-not $SkipVerify) {
        $restored = @(Get-ChildItem -LiteralPath $script:StoragePath -Recurse -File -Force -ErrorAction Stop)
        $archivedFiles = 0
        foreach ($entry in $listing) {
            $text = [string]$entry
            if ($text -and -not $text.EndsWith('/') -and -not $text.EndsWith('\')) {
                $archivedFiles++
            }
        }
        Write-Log ("Extracted {0} files, archive has {1}" -f $restored.Count, $archivedFiles) $logFile
        if ($restored.Count -lt $archivedFiles) {
            throw ("Extracted file count ({0}) is less than in the archive ({1})." -f $restored.Count, $archivedFiles)
        }
    }

    Write-Log 'Storage restore finished.' $logFile

    if ($backupPath) {
        if ($RemoveOldOnSuccess) {
            Write-Log ("Removing previous storage: {0}" -f $backupPath) $logFile
            Remove-Item -LiteralPath $backupPath -Recurse -Force
            Write-Log 'Previous storage removed.' $logFile
        } else {
            Write-Log ("Previous storage kept at: {0}" -f $backupPath) $logFile
            Write-Log 'Delete it manually after verifying the restored data.' $logFile
        }
    }

    Write-Log 'Start the application and check posters and avatars.' $logFile
}
catch {
    Write-Log ('ERROR: ' + $_.Exception.Message) $logFile 'ERROR'
    $exitCode = 1

    if ($backupPath -and (Test-Path -LiteralPath $backupPath)) {
        try {
            if (Test-Path -LiteralPath $script:StoragePath) {
                Remove-Item -LiteralPath $script:StoragePath -Recurse -Force -ErrorAction SilentlyContinue
            }
            Move-Item -LiteralPath $backupPath -Destination $script:StoragePath -Force
            Write-Log 'Rolled back to the previous storage folder.' $logFile 'WARN'
        } catch {
            Write-Log ('Rollback failed: ' + $_.Exception.Message) $logFile 'ERROR'
            Write-Log ("Previous storage is still at: {0}" -f $backupPath) $logFile 'ERROR'
        }
    } elseif (Test-Path -LiteralPath $script:StoragePath) {
        try {
            Remove-Item -LiteralPath $script:StoragePath -Recurse -Force
            Write-Log 'Removed partially extracted storage folder.' $logFile 'WARN'
        } catch {
            Write-Log ('Could not remove partial extraction: ' + $_.Exception.Message) $logFile 'WARN'
        }
    }
}

if ($exitCode -eq 0) {
    Write-Log 'Storage restore finished successfully.' $logFile
} else {
    Write-Log 'Storage restore FAILED.' $logFile 'ERROR'
}

exit $exitCode
