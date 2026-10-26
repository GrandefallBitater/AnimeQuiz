[CmdletBinding()]
param(
    [string]$BackupRoot,
    [string]$Source,
    [int]$KeepArchives = 4,
    [switch]$SkipVerify
)

. (Join-Path $PSScriptRoot '_common.ps1')

if ($BackupRoot) {
    $script:BackupRoot = $BackupRoot
    $script:DbDir      = Join-Path $BackupRoot 'db'
    $script:StorageDir = Join-Path $BackupRoot 'storage'
    $script:LogDir     = Join-Path $BackupRoot 'logs'
}

if ($Source) { $script:StoragePath = $Source }

Initialize-BackupDirectories
$logFile = Join-Path $script:LogDir ('backup-storage-' + (Get-Date -Format 'yyyy-MM') + '.log')
$partial = $null

$lock = Enter-BackupLock -Name 'storage'
if (-not $lock) {
    Write-Log 'Another storage backup is already running. Skipping this run.' $logFile 'WARN'
    exit 0
}

$exitCode = 0

try {
    $sourceRoot = [System.IO.Path]::GetFullPath($script:StoragePath).TrimEnd('\', '/')

    if (-not (Test-Path -LiteralPath $sourceRoot)) {
        throw "Storage directory not found: $sourceRoot"
    }

    $tar = Join-Path $env:SystemRoot 'System32\tar.exe'
    if (-not (Test-Path -LiteralPath $tar)) {
        $cmd = Get-Command tar.exe -ErrorAction SilentlyContinue
        if (-not $cmd) { throw 'tar.exe not found. Windows 10 1803+ or newer is required.' }
        $tar = $cmd.Source
    }

    Write-Log ('Source: ' + $sourceRoot) $logFile
    Write-Log ('tar: ' + $tar) $logFile

    Write-Log 'Scanning source directory...' $logFile
    $files = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Force -ErrorAction Stop)
    $fileCount = $files.Count
    $totalBytes = ($files | Measure-Object -Property Length -Sum).Sum
    $totalMb = [math]::Round($totalBytes / 1MB, 1)

    if ($fileCount -eq 0) {
        throw "Storage directory is empty: $sourceRoot"
    }

    Write-Log ("Source contains {0} files, {1} MB" -f $fileCount, $totalMb) $logFile

    if ($fileCount -gt 65000) {
        Write-Log ("WARNING: {0} files; Windows Explorer cannot open ZIP archives with more than 65535 entries." -f $fileCount) $logFile 'WARN'
    }

    $requiredGb = [math]::Ceiling($totalBytes / 1GB) + 2
    if ($requiredGb -lt $script:MinFreeSpaceGb) { $requiredGb = $script:MinFreeSpaceGb }
    $freeGb = Test-FreeSpace -Path $script:BackupRoot -RequiredGb $requiredGb
    Write-Log ("Free space: {0} GB (required at least {1} GB)" -f $freeGb, $requiredGb) $logFile

    $stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
    $archiveFile = Join-Path $script:StorageDir ('storage_{0}.zip' -f $stamp)
    $partial = $archiveFile + '.part'

    $listFile = Join-Path ([System.IO.Path]::GetTempPath()) ('animequiz-storage-' + [guid]::NewGuid().ToString('N') + '.list')
    try {
        Write-Log 'Building file list...' $logFile
        $builder = New-Object System.Text.StringBuilder
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($sourceRoot.Length).TrimStart('\', '/').Replace('\', '/')
            [void]$builder.Append($relative).Append([char]0)
        }
        [System.IO.File]::WriteAllText($listFile, $builder.ToString(), $script:Utf8NoBom)

        $arguments = @(
            '--format=zip', '-c',
            '--options=zip:compression=store',
            '-f', $partial,
            '-C', $sourceRoot,
            '--null', '-T', $listFile
        )

        $code = Invoke-Tool -FilePath $tar -Arguments $arguments -LogFile $logFile -Quiet
        if ($code -ne 0) { throw "tar failed with exit code $code" }
    } finally {
        Remove-Item -LiteralPath $listFile -Force -ErrorAction SilentlyContinue
    }

    if (-not (Test-Path -LiteralPath $partial)) {
        throw 'tar reported success but did not create an archive.'
    }

    $size = (Get-Item -LiteralPath $partial).Length
    if ($size -lt 1024) {
        throw "Archive is suspiciously small: $size bytes."
    }

    if (-not $SkipVerify) {
        Write-Log 'Verifying archive contents...' $logFile
        $listing = @(& $tar -t -f $partial 2>&1)
        $listCode = $LASTEXITCODE
        if ($listCode -ne 0) { throw "tar -t failed with exit code $listCode" }

        $archivedFiles = 0
        foreach ($entry in $listing) {
            $text = [string]$entry
            if ($text -and -not $text.EndsWith('/') -and -not $text.EndsWith('\')) {
                $archivedFiles++
            }
        }

        Write-Log ("Archive listing: {0} files" -f $archivedFiles) $logFile
        if ($archivedFiles -ne $fileCount) {
            throw ("Archive content mismatch: source has {0} files, archive has {1}." -f $fileCount, $archivedFiles)
        }
        Write-Log 'Archive verified: file count matches the source.' $logFile
    }

    Move-Item -LiteralPath $partial -Destination $archiveFile -Force
    $partial = $null

    Write-Log ("BACKUP OK: {0} ({1:N1} MB)" -f $archiveFile, ($size / 1MB)) $logFile

    $limit = [math]::Max(1, $KeepArchives)
    $archives = @(Get-ChildItem -LiteralPath $script:StorageDir -Filter 'storage_*.zip' -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTime -Descending)
    if ($archives.Count -gt $limit) {
        $archives | Select-Object -Skip $limit | ForEach-Object {
            try {
                Remove-Item -LiteralPath $_.FullName -Force
                Write-Log ("Deleted old archive: {0}" -f $_.Name) $logFile
            } catch {
                Write-Log ("Could not delete {0}: {1}" -f $_.Name, $_.Exception.Message) $logFile 'WARN'
            }
        }
    }

    Get-ChildItem -LiteralPath $script:StorageDir -Filter '*.part' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
        ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
            Write-Log ("Deleted stale partial file: {0}" -f $_.Name) $logFile 'WARN'
        }

    $remaining = @(Get-ChildItem -LiteralPath $script:StorageDir -Filter 'storage_*.zip' -File -ErrorAction SilentlyContinue).Count
    Write-Log ("Stored storage archives: {0} (keep {1})" -f $remaining, $limit) $logFile
}
catch {
    Write-Log ('ERROR: ' + $_.Exception.Message) $logFile 'ERROR'
    if ($partial -and (Test-Path -LiteralPath $partial)) {
        Write-Log ('Incomplete archive kept for inspection: ' + $partial) $logFile 'WARN'
    }
    $exitCode = 1
}
finally {
    Exit-BackupLock $lock
}

if ($exitCode -eq 0) {
    Write-Log 'Storage backup finished.' $logFile
} else {
    Write-Log 'Storage backup FAILED.' $logFile 'ERROR'
}

exit $exitCode
