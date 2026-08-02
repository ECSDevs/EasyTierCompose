<#
.SYNOPSIS
  EasyTierCompose release helper.

.DESCRIPTION
  Automates the release flow:
    1. Validates the working tree is clean and on the main branch.
    2. Bumps version.txt (explicit version or major/minor/patch bump).
    3. Commits the version bump.
    4. Creates an annotated tag (v<version>).
    5. Pushes main and the tag to origin, which triggers the Release workflow.

.PARAMETER Version
  Explicit semantic version to release, e.g. "1.2.0". Mutually exclusive with
  -Major / -Minor / -Patch.

.PARAMETER Major
  Bump the major component (1.2.3 -> 2.0.0).

.PARAMETER Minor
  Bump the minor component (1.2.3 -> 1.3.0).

.PARAMETER Patch
  Bump the patch component (1.2.3 -> 1.2.4). Default when no bump flag is given.

.PARAMETER NoPush
  Perform every local step but do not push to origin.

.PARAMETER Force
  Skip the clean-tree and main-branch guards. Use with caution.

.EXAMPLE
  ./release.ps1 -Patch
  Bump patch and release (default behavior).

.EXAMPLE
  ./release.ps1 -Version 1.5.0
  Release an explicit version.

.EXAMPLE
  ./release.ps1 -Minor -NoPush
  Bump minor locally without pushing.
#>

[CmdletBinding(SupportsShouldProcess = $false)]
param(
    [string]$Version,
    [switch]$Major,
    [switch]$Minor,
    [switch]$Patch,
    [switch]$NoPush,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

# --- argument validation ---------------------------------------------------

$bumpFlags = @($Major, $Minor, $Patch | Where-Object { $_ })
if ($Version -and $bumpFlags.Count -gt 0) {
    throw '-Version is mutually exclusive with -Major/-Minor/-Patch.'
}
if (-not $Version -and $bumpFlags.Count -gt 1) {
    throw 'Only one of -Major/-Minor/-Patch may be specified.'
}

# --- helpers ---------------------------------------------------------------

function Invoke-Git([string[]]$GitArgs) {
    & git @GitArgs
    if ($LASTEXITCODE -ne 0) {
        throw "git $($GitArgs -join ' ') failed with exit code $LASTEXITCODE."
    }
}

function Test-SemVer([string]$v) {
    return $v -match '^\d+\.\d+\.\d+$'
}

function Get-CurrentVersion {
    $path = Join-Path $repoRoot 'version.txt'
    if (-not (Test-Path $path)) { return $null }
    $raw = (Get-Content $path -Raw).Trim()
    if (-not $raw) { return $null }
    return $raw
}

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

# --- guards ----------------------------------------------------------------

$cleanOutput = (git status --porcelain) 2>&1
if ($LASTEXITCODE -ne 0) { throw "git status failed." }
if ($cleanOutput -and -not $Force) {
    throw "Working tree is not clean. Commit or stash your changes first, or rerun with -Force."
}

$currentBranch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "git rev-parse failed." }
if ($currentBranch -ne 'main' -and -not $Force) {
    throw "Current branch is '$currentBranch', expected 'main'. Rerun with -Force to override."
}

# --- resolve target version ------------------------------------------------

$current = Get-CurrentVersion
if (-not $current) {
    $current = '0.0.0'
    Write-Warning "version.txt missing or empty; assuming 0.0.0 as the baseline."
}
if (-not (Test-SemVer $current)) {
    throw "Current version '$current' is not a valid semantic version (X.Y.Z)."
}

if ($Version) {
    if (-not (Test-SemVer $Version)) {
        throw "Provided -Version '$Version' is not a valid semantic version (X.Y.Z)."
    }
    $target = $Version
}
else {
    $parts = $current.Split('.') | ForEach-Object { [int]$_ }
    if ($Major) {
        $parts[0] += 1; $parts[1] = 0; $parts[2] = 0
    }
    elseif ($Minor) {
        $parts[1] += 1; $parts[2] = 0
    }
    else {
        # Default to patch bump when no flag is given.
        $parts[2] += 1
    }
    $target = ($parts -join '.')
}

if ($target -eq $current) {
    throw "Target version '$target' equals current version '$current'. Nothing to release."
}

# Confirm the tag does not already exist.
$existingTag = git rev-parse -q --verify "refs/tags/v$target" 2>$null
if ($LASTEXITCODE -eq 0 -or $existingTag) {
    throw "Tag 'v$target' already exists."
}

# --- summary ---------------------------------------------------------------

Write-Host ""
Write-Host "EasyTierCompose release" -ForegroundColor Green
Write-Host "  Current version : $current"
Write-Host "  Target  version : $target"
Write-Host "  Branch          : $currentBranch"
Write-Host "  Push            : $(if ($NoPush) { 'no' } else { 'yes' })"

# --- execute ---------------------------------------------------------------

Write-Step "Update version.txt -> $target"
Set-Content -Path (Join-Path $repoRoot 'version.txt') -Value "$target`n" -NoNewline:$false -Encoding utf8

Write-Step "Stage and commit version bump"
Invoke-Git @('add', 'version.txt')
Invoke-Git @('commit', '-m', "release: v$target")

Write-Step "Create annotated tag v$target"
Invoke-Git @('tag', '-a', "v$target", '-m', "EasyTierCompose v$target")

if ($NoPush) {
    Write-Host ""
    Write-Host "Skipping push (-NoPush). Run manually when ready:" -ForegroundColor Yellow
    Write-Host "  git push origin $currentBranch"
    Write-Host "  git push origin v$target"
}
else {
    Write-Step "Push $currentBranch and tag v$target to origin"
    Invoke-Git @('push', 'origin', $currentBranch)
    Invoke-Git @('push', 'origin', "v$target")
}

Write-Host ""
Write-Host "Done. Release v$target dispatched." -ForegroundColor Green
if (-not $NoPush) {
    Write-Host "Track the Release workflow: GitHub Actions -> Release APK." -ForegroundColor DarkGray
}
