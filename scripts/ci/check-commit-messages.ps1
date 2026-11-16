# Checks the subjects of the commits that are being pushed. The rules follow
# AGENTS.md: English, short, imperative, no trailing period, and an empty line
# between the subject and the body. Merge commits are ignored.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Range
)

$ErrorActionPreference = 'Stop'

$parts = $Range -split '\.\.', 2
$base = $parts[0]
$head = if ($parts.Count -gt 1) { $parts[1] } else { '' }

# A new branch reports an all-zero "before" commit. Compare it with the usual
# integration branches instead.
if ([string]::IsNullOrWhiteSpace($base) -or $base -match '^0+$') {
    $base = ''
    foreach ($fallback in @('origin/dev', 'origin/main')) {
        git rev-parse --verify --quiet "$fallback^{commit}" | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $base = $fallback
            break
        }
    }
}

if ([string]::IsNullOrWhiteSpace($base) -or [string]::IsNullOrWhiteSpace($head)) {
    Write-Host 'The commit range is unknown, skipping the commit message check.'
    exit 0
}

$range = "$base..$head"
$hashes = @(git rev-list --no-merges $range)
if ($LASTEXITCODE -ne 0) {
    throw "Cannot read the commits in $range."
}

$errors = @()
foreach ($hash in $hashes) {
    $short = $hash.Substring(0, 7)
    $subject = (git log -1 --format=%s $hash).Trim()

    if ([string]::IsNullOrWhiteSpace($subject)) {
        $errors += "$short : the subject is empty"
        continue
    }

    if ($subject.Length -gt 72) {
        $errors += "$short : the subject is $($subject.Length) characters long, the limit is 72"
    }

    if ($subject.EndsWith('.')) {
        $errors += "$short : the subject ends with a period"
    }

    if ($subject -match '[^\x20-\x7E]') {
        $errors += "$short : the subject must be in English"
    }

    $body = (git log -1 --format=%b $hash).Trim()
    if (-not [string]::IsNullOrWhiteSpace($body)) {
        $raw = (git log -1 --format=%B $hash) -join "`n"
        if ($raw -notmatch '^[^\r\n]+\r?\n\r?\n') {
            $errors += "$short : put an empty line between the subject and the body"
        }
    }
}

if ($errors.Count -gt 0) {
    foreach ($message in $errors) {
        Write-Host "::error::$message"
    }
    throw "Found $($errors.Count) commit message problem(s) in $range."
}

Write-Host "Checked $($hashes.Count) commit(s) in $range."
