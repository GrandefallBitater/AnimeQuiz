# Requires the version in pom.xml to be higher than the version on the base
# branch. Used for pull requests into main, where the release version is bumped
# before merging.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BaseRef
)

$ErrorActionPreference = 'Stop'

function Get-PomVersion {
    param([string] $Xml)

    $document = [xml] $Xml
    $version = [string] $document.project.version
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw 'Cannot find <version> in pom.xml.'
    }
    return $version
}

function ConvertTo-BuildVersion {
    param([string] $Version)

    $numeric = $Version -replace '-SNAPSHOT$', ''
    return [version] $numeric
}

$baseXml = (git show "${BaseRef}:pom.xml" 2>$null) -join "`n"
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($baseXml)) {
    Write-Host "There is no pom.xml in $BaseRef, skipping the version check."
    exit 0
}

$baseVersion = Get-PomVersion $baseXml
$headVersion = Get-PomVersion (Get-Content -Raw 'pom.xml')

$baseBuild = ConvertTo-BuildVersion $baseVersion
$headBuild = ConvertTo-BuildVersion $headVersion

if ($headBuild -le $baseBuild) {
    throw "The project version is $headVersion, but $BaseRef already has $baseVersion. Bump the third number in pom.xml before merging."
}

Write-Host "Version $headVersion is higher than $baseVersion in $BaseRef."
