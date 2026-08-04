<#
.SYNOPSIS
  Build EasyTier-Core's Android JNI library and copy it into jniLibs.

.DESCRIPTION
  Wraps EasyTier-Core/easytier-contrib/easytier-android-jni/build.ps1:
    1. Ensures the EasyTier-Core submodule is present and checked out.
    2. Invokes the Windows build script for the requested ABIs.
    3. Copies the freshly built libeasytier_android_jni.so files into
       app/src/main/jniLibs/<abi>/, overwriting the existing prebuilt copies.

  Run from the EasyTierCompose repository root. Requires Rust + cargo-ndk
  + Android NDK on PATH (the underlying build.ps1 performs the checks and
  will install cargo-ndk / missing rustup targets on demand).

.PARAMETER Targets
  Comma-separated list of Android ABIs to build. Defaults to
  "arm64-v8a,armeabi-v7a,x86_64" to match app/build.gradle.kts abiFilters.

.PARAMETER SkipBuild
  Skip the cargo-ndk build step and only copy artifacts that already exist
  under EasyTier-Core/.../target/android/. Useful for re-applying a prior
  local build.

.PARAMETER SkipInstallTargets
  Forwarded to build.ps1; do not rustup target add missing targets.

.EXAMPLE
  ./update-jni.ps1
  Build all default ABIs and refresh jniLibs.

.EXAMPLE
  ./update-jni.ps1 -Targets "arm64-v8a"
  Build and refresh a single ABI.
#>

[CmdletBinding(SupportsShouldProcess = $false)]
param(
    [string]$Targets = "arm64-v8a,armeabi-v7a,x86_64",
    [switch]$SkipBuild,
    [switch]$SkipInstallTargets
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

# --- paths -----------------------------------------------------------------
$coreSubmodule  = Join-Path $repoRoot 'EasyTier-Core'
$jniBuildDir    = Join-Path $coreSubmodule 'easytier-contrib\easytier-android-jni'
$buildScript    = Join-Path $jniBuildDir 'build.ps1'
$artifactRoot   = Join-Path $jniBuildDir 'target\android'
$jniLibsRoot    = Join-Path $repoRoot 'app\src\main\jniLibs'

# --- helpers ---------------------------------------------------------------
function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}
function Write-Info([string]$msg) { Write-Host $msg -ForegroundColor Green }
function Write-Warn([string]$msg) { Write-Host $msg -ForegroundColor Yellow }
function Write-Err([string]$msg)  { Write-Host $msg -ForegroundColor Red }

# --- validate submodule ----------------------------------------------------
if (-not (Test-Path -LiteralPath $buildScript)) {
    Write-Err "Build script not found: $buildScript"
    Write-Err "Is the EasyTier-Core submodule initialized? Run:"
    Write-Err "  git submodule update --init --recursive"
    exit 1
}

$abIs = $Targets -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
if (-not $abIs) {
    throw "No targets parsed from -Targets '$Targets'."
}

# --- build -----------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Step "Building EasyTier Android JNI for: $($abIs -join ', ')"
    # Use hashtable splatting so -Targets binds unambiguously; array splatting
    # of @('-Targets', '<comma-string>') can mis-bind in some PowerShell versions.
    $buildParams = @{
        Targets = ($abIs -join ',')
    }
    if ($SkipInstallTargets) { $buildParams['SkipInstallTargets'] = $true }
    & $buildScript @buildParams
    if ($LASTEXITCODE -ne 0) {
        throw "build.ps1 failed with exit code $LASTEXITCODE."
    }
} else {
    Write-Step "Skipping build (-SkipBuild); reusing existing artifacts"
}

# --- copy artifacts into jniLibs -------------------------------------------
Write-Step "Refreshing jniLibs from $artifactRoot"

$copied = 0
$missing = @()
foreach ($abi in $abIs) {
    $src = Join-Path $artifactRoot "$abi\libeasytier_android_jni.so"
    $dstDir = Join-Path $jniLibsRoot $abi
    $dst = Join-Path $dstDir 'libeasytier_android_jni.so'

    if (-not (Test-Path -LiteralPath $src)) {
        $missing += $src
        continue
    }

    New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
    Copy-Item -LiteralPath $src -Destination $dst -Force
    Write-Info "  $abi <- $src"
    $copied++
}

Write-Host ""
if ($missing.Count -gt 0) {
    Write-Warn "Missing artifacts (not copied):"
    foreach ($m in $missing) { Write-Warn "  $m" }
}
Write-Info "Copied $copied library file(s) to $jniLibsRoot"

if ($copied -eq 0) {
    Write-Err "No libraries were copied. Check the build output above."
    exit 1
}

Write-Host ""
Write-Info "Done. jniLibs refreshed for: $($abIs -join ', ')"
