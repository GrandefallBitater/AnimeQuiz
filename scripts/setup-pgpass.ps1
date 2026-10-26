[CmdletBinding()]
param(
    [string]$Database = '*',
    [string]$User = 'postgres',
    [string]$HostName = 'localhost',
    [int]$Port = 5433,
    [string]$Password,
    [switch]$NoVerify
)

. (Join-Path $PSScriptRoot '_common.ps1')

if (-not $Password) {
    $secure = Read-Host -AsSecureString -Prompt ("Password for PostgreSQL user '{0}'" -f $User)
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $Password = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

if (-not $Password) {
    throw 'Password must not be empty.'
}

Initialize-BackupDirectories
$logFile = Join-Path $script:LogDir ('setup-pgpass-' + (Get-Date -Format 'yyyy-MM') + '.log')
$pgpass = Resolve-PgPassFile
$pgpassDir = Split-Path -Parent $pgpass

if (-not (Test-Path -LiteralPath $pgpassDir)) {
    New-Item -ItemType Directory -Path $pgpassDir -Force | Out-Null
    Write-Log "Created directory: $pgpassDir" $logFile
}

function ConvertTo-PgPassField {
    param([string]$Value)
    return $Value.Replace('\', '\\').Replace(':', '\:')
}

$entry = '{0}:{1}:{2}:{3}:{4}' -f (ConvertTo-PgPassField $HostName),
    $Port,
    (ConvertTo-PgPassField $Database),
    (ConvertTo-PgPassField $User),
    (ConvertTo-PgPassField $Password)

$existing = @()
if (Test-Path -LiteralPath $pgpass) {
    $existing = @(Get-Content -LiteralPath $pgpass -ErrorAction SilentlyContinue)
}

if ($Database -eq '*') {
    $databasePattern = '[^:]*'
} else {
    $databasePattern = [regex]::Escape((ConvertTo-PgPassField $Database))
}

$pattern = '^{0}:{1}:{2}:{3}:' -f [regex]::Escape((ConvertTo-PgPassField $HostName)),
    $Port,
    $databasePattern,
    [regex]::Escape((ConvertTo-PgPassField $User))

$kept = @($existing | Where-Object { $_ -and ($_ -notmatch $pattern) })
$content = ($kept + $entry) -join "`n"
[System.IO.File]::WriteAllText($pgpass, $content + "`n", $script:Utf8NoBom)

Write-Log "Wrote credentials for $HostName`:$Port/$Database to $pgpass" $logFile

$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $pgpass /inheritance:r /grant:r ("*{0}:(F)" -f $sid) | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Log 'WARNING: could not restrict pgpass ACL. Check the file permissions manually.' $logFile 'WARN'
} else {
    Write-Log 'pgpass ACL restricted to the current user.' $logFile
}

if (-not $NoVerify) {
    try {
        Set-PgEnvironment
        $psql = Resolve-PgTool -Name 'psql'

        $code = Invoke-Tool -FilePath $psql -Arguments @(
            '-h', $HostName,
            '-p', [string]$Port,
            '-U', $User,
            '-d', $script:PgDatabase,
            '-w',
            '-t', '-A',
            '-v', 'ON_ERROR_STOP=1',
            '-c', 'select current_user || ''@'' || current_database();'
        ) -LogFile $logFile

        if ($code -ne 0) {
            throw "psql verification failed with exit code $code"
        }
        Write-Log 'Verification OK: psql connected without a password prompt.' $logFile
    } catch {
        Write-Log ('ERROR: ' + $_.Exception.Message) $logFile 'ERROR'
        Write-Log 'Check that PostgreSQL is running and the password is correct.' $logFile 'ERROR'
        exit 1
    }
}

Write-Log 'Done.' $logFile
exit 0
