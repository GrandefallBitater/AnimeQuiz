[CmdletBinding()]
param(
    [string]$DbTaskName = 'AnimeQuiz DB Backup',
    [string]$StorageTaskName = 'AnimeQuiz Storage Backup',
    [string]$DbTime = '03:00',
    [string]$StorageDay = 'Sunday',
    [string]$StorageTime = '03:30',
    [switch]$Unregister
)

. (Join-Path $PSScriptRoot '_common.ps1')

Initialize-BackupDirectories
$logFile = Join-Path $script:LogDir ('tasks-' + (Get-Date -Format 'yyyy-MM') + '.log')

if ($Unregister) {
    foreach ($name in @($DbTaskName, $StorageTaskName)) {
        try {
            Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction Stop
            Write-Log ("Removed task: {0}" -f $name) $logFile
        } catch {
            Write-Log ("Task not found or could not be removed: {0} ({1})" -f $name, $_.Exception.Message) $logFile 'WARN'
        }
    }
    exit 0
}

$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (-not (Test-Path -LiteralPath $powershell)) {
    $powershell = 'powershell.exe'
}

$dbScript = Join-Path $PSScriptRoot 'backup-db.ps1'
$storageScript = Join-Path $PSScriptRoot 'backup-storage.ps1'

foreach ($scriptFile in @($dbScript, $storageScript)) {
    if (-not (Test-Path -LiteralPath $scriptFile)) {
        throw "Script not found: $scriptFile"
    }
}

$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 4)

$currentUser = $env:USERNAME
if ($env:USERDOMAIN) { $currentUser = $env:USERDOMAIN + '\' + $env:USERNAME }

function Register-AnimeQuizTask {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Action,
        [Parameter(Mandatory = $true)]$Trigger,
        [Parameter(Mandatory = $true)][string]$Description
    )

    try {
        $principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType S4U -RunLevel Limited
        $task = Register-ScheduledTask -TaskName $Name -Action $Action -Trigger $Trigger -Settings $settings -Principal $principal -Description $Description -Force -ErrorAction Stop
        Write-Log ("Registered task (S4U): {0}" -f $Name) $logFile
        return $task
    } catch {
        Write-Log ("S4U registration failed for '{0}': {1}" -f $Name, $_.Exception.Message) $logFile 'WARN'
    }

    try {
        $principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Limited
        $task = Register-ScheduledTask -TaskName $Name -Action $Action -Trigger $Trigger -Settings $settings -Principal $principal -Description $Description -Force -ErrorAction Stop
        Write-Log ("Registered task (Interactive, user must be logged on): {0}" -f $Name) $logFile 'WARN'
        return $task
    } catch {
        Write-Log ("Could not register task '{0}': {1}" -f $Name, $_.Exception.Message) $logFile 'ERROR'
        return $null
    }
}

$dbAction = New-ScheduledTaskAction `
    -Execute $powershell `
    -Argument ('-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $dbScript) `
    -WorkingDirectory $PSScriptRoot

$storageAction = New-ScheduledTaskAction `
    -Execute $powershell `
    -Argument ('-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $storageScript) `
    -WorkingDirectory $PSScriptRoot

$dbTrigger = New-ScheduledTaskTrigger -Daily -At $DbTime
$storageTrigger = New-ScheduledTaskTrigger -Weekly -DaysOfWeek $StorageDay -At $StorageTime

$dbTask = Register-AnimeQuizTask -Name $DbTaskName -Action $dbAction -Trigger $dbTrigger -Description 'Daily PostgreSQL dump of the AnimeQuiz database'
$storageTask = Register-AnimeQuizTask -Name $StorageTaskName -Action $storageAction -Trigger $storageTrigger -Description 'Weekly zip archive of the AnimeQuiz posters and avatars'

if (-not $dbTask -or -not $storageTask) {
    Write-Log 'One or more tasks were not registered.' $logFile 'ERROR'
    Write-Log 'Try running this script from an elevated PowerShell window (Run as administrator).' $logFile
    exit 1
}

foreach ($name in @($DbTaskName, $StorageTaskName)) {
    try {
        $info = Get-ScheduledTaskInfo -TaskName $name -ErrorAction Stop
        $task = Get-ScheduledTask -TaskName $name -ErrorAction Stop
        Write-Log ("Task '{0}': state={1}, next run={2}" -f $name, $task.State, $info.NextRunTime) $logFile
    } catch {
        Write-Log ("Could not read info for task '{0}': {1}" -f $name, $_.Exception.Message) $logFile 'WARN'
    }
}

Write-Log 'Done. Check the tasks in taskschd.msc.' $logFile
Write-Log 'Run a task manually: Start-ScheduledTask -TaskName "AnimeQuiz DB Backup"' $logFile

exit 0
