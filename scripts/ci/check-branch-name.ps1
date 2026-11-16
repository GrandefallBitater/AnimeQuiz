# Checks that a branch name is a safe git ref and stays within the project
# convention. The rule is intentionally soft: the repository already uses names
# such as "CI_CD" and "DEV_CI_CD", so only characters that break git or tooling
# are rejected.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Branch
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Branch)) {
    throw 'The branch name is empty.'
}

if ($Branch -notmatch '^[A-Za-z0-9][A-Za-z0-9._/-]*$') {
    throw "Branch '$Branch' contains unsupported characters. Use letters, digits, '.', '_', '-' and '/'."
}

if ($Branch -match '\.\.' -or $Branch -match '@\{' -or $Branch -match '//') {
    throw "Branch '$Branch' contains a sequence that git does not allow."
}

if ($Branch.EndsWith('/') -or $Branch.EndsWith('.')) {
    throw "Branch '$Branch' must not end with '/' or '.'."
}

Write-Host "Branch name '$Branch' is fine."
