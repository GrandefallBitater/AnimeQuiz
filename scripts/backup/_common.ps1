$ErrorActionPreference = 'Stop'

$script:BackupRoot = if ($env:ANIMEQUIZ_BACKUP_ROOT) {
    $env:ANIMEQUIZ_BACKUP_ROOT
} else {
    Join-Path $env:USERPROFILE 'Backups\AnimeQuiz'
}

$script:DbDir      = Join-Path $script:BackupRoot 'db'
$script:StorageDir = Join-Path $script:BackupRoot 'storage'
$script:LogDir     = Join-Path $script:BackupRoot 'logs'

$script:PgHost     = if ($env:ANIMEQUIZ_PG_HOST) { $env:ANIMEQUIZ_PG_HOST } else { 'localhost' }
$script:PgPort     = if ($env:ANIMEQUIZ_PG_PORT) { [int]$env:ANIMEQUIZ_PG_PORT } else { 5433 }
$script:PgDatabase = if ($env:ANIMEQUIZ_PG_DB) { $env:ANIMEQUIZ_PG_DB } else { 'AnimeQuiz' }
$script:PgUser     = if ($env:ANIMEQUIZ_PG_USER) { $env:ANIMEQUIZ_PG_USER } else { 'postgres' }

$script:StoragePath = if ($env:ANIMEQUIZ_STORAGE) {
    $env:ANIMEQUIZ_STORAGE
} else {
    Join-Path $env:USERPROFILE '.animequiz\storage'
}

$script:AppPort        = if ($env:ANIMEQUIZ_APP_PORT) { [int]$env:ANIMEQUIZ_APP_PORT } else { 8081 }
$script:MinFreeSpaceGb = 5
$script:Utf8NoBom      = New-Object System.Text.UTF8Encoding -ArgumentList $false

function Initialize-BackupDirectories {
    foreach ($dir in @($script:BackupRoot, $script:DbDir, $script:StorageDir, $script:LogDir)) {
        if (-not (Test-Path -LiteralPath $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
    }
}

function Write-Log {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [string]$LogFile,
        [ValidateSet('INFO', 'WARN', 'ERROR')][string]$Level = 'INFO'
    )

    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    $line = $line.Replace([char]0x00A0, ' ').Replace([char]0x202F, ' ')
    Write-Host $line

    if ($LogFile) {
        $parent = Split-Path -Parent $LogFile
        if ($parent -and -not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }
        [System.IO.File]::AppendAllText($LogFile, $line + [Environment]::NewLine, $script:Utf8NoBom)
    }
}

function Resolve-PgTool {
    param(
        [Parameter(Mandatory = $true)][string]$Name
    )

    $exe = if ($Name -match '\.exe$') { $Name } else { $Name + '.exe' }

    $candidates = @()

    if ($env:ANIMEQUIZ_PG_BIN) {
        $candidates += Join-Path $env:ANIMEQUIZ_PG_BIN $exe
    }

    $base = Join-Path $env:ProgramFiles 'PostgreSQL'
    if (Test-Path -LiteralPath $base) {
        $versionDirs = Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\d+(\.\d+)*$' } |
            Sort-Object -Property @{ Expression = {
                (($_.Name -split '\.') | ForEach-Object { $_.PadLeft(6, '0') }) -join '.'
            } } -Descending

        foreach ($dir in $versionDirs) {
            $candidates += Join-Path (Join-Path $dir.FullName 'bin') $exe
        }
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }

    $cmd = Get-Command $exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    throw "PostgreSQL tool not found: $exe. Install PostgreSQL client tools or set ANIMEQUIZ_PG_BIN."
}

function Resolve-PgPassFile {
    if ($env:PGPASSFILE) { return $env:PGPASSFILE }
    return (Join-Path $env:APPDATA 'postgresql\pgpass.conf')
}

function Set-PgEnvironment {
    $pgpass = Resolve-PgPassFile
    if (-not (Test-Path -LiteralPath $pgpass)) {
        throw "pgpass file not found: $pgpass. Run scripts\backup\setup-pgpass.ps1 first."
    }
    $env:PGPASSFILE = $pgpass
    $env:PGCLIENTENCODING = 'UTF8'
    $env:PGCONNECT_TIMEOUT = '15'
}

function Test-FreeSpace {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [double]$RequiredGb = $script:MinFreeSpaceGb
    )

    $full = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($full)
    $drive = New-Object System.IO.DriveInfo -ArgumentList $root

    if (-not $drive.IsReady) {
        throw "Drive is not ready: $root"
    }

    $freeGb = [math]::Round($drive.AvailableFreeSpace / 1GB, 2)
    if ($freeGb -lt $RequiredGb) {
        throw ("Not enough free space on {0}: {1} GB available, {2} GB required." -f $root, $freeGb, $RequiredGb)
    }

    return $freeGb
}

function Enter-BackupLock {
    param(
        [Parameter(Mandatory = $true)][string]$Name
    )

    $mutex = New-Object System.Threading.Mutex -ArgumentList $false, ("Local\AnimeQuizBackup_" + $Name)
    if (-not $mutex.WaitOne(0)) {
        $mutex.Dispose()
        return $null
    }
    return $mutex
}

function Exit-BackupLock {
    param($Mutex)

    if ($Mutex) {
        try { $Mutex.ReleaseMutex() } catch { }
        $Mutex.Dispose()
    }
}

function Invoke-Tool {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$LogFile,
        [switch]$Quiet
    )

    Write-Log ("Run: {0} {1}" -f [System.IO.Path]::GetFileName($FilePath), ($Arguments -join ' ')) $LogFile

    $lines = @(& $FilePath @Arguments 2>&1)
    $code = $LASTEXITCODE

    if ($lines.Count -gt 0) {
        if (-not ($Quiet -and $code -eq 0)) {
            $max = $lines.Count
            if ($max -gt 40) { $max = 40 }
            for ($i = 0; $i -lt $max; $i++) {
                $text = [string]$lines[$i]
                if ($text.Trim()) { Write-Log ('  ' + $text) $LogFile }
            }
            if ($lines.Count -gt $max) {
                Write-Log ('  ... {0} more line(s)' -f ($lines.Count - $max)) $LogFile
            }
        }
    }

    return $code
}

function Test-AppListening {
    try {
        $connections = Get-NetTCPConnection -State Listen -LocalPort $script:AppPort -ErrorAction SilentlyContinue
        return [bool]$connections
    } catch {
        return $false
    }
}

function Get-NewestFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Filter
    )

    $file = Get-ChildItem -LiteralPath $Path -Filter $Filter -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $file) { return $null }
    return $file.FullName
}

function Invoke-DbQuery {
    param(
        [Parameter(Mandatory = $true)][string]$Psql,
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$Query,
        [string]$LogFile
    )

    $lines = @(& $Psql -h $script:PgHost -p $script:PgPort -U $script:PgUser -d $Database -w -t -A -v ON_ERROR_STOP=1 -c $Query 2>&1)
    $code = $LASTEXITCODE
    foreach ($line in $lines) {
        $text = [string]$line
        if ($text.Trim()) { Write-Log ('  ' + $text) $LogFile }
    }
    return $code
}
