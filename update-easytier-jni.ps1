#Requires -Version 5.1
<#
.SYNOPSIS
    One-click rebuild of libeasytier_android_jni.so from the EasyTier-Core submodule
    and sync into app/src/main/jniLibs/<abi>/.

.DESCRIPTION
    Windows port of EasyTier-Core's easytier-android-jni/build.sh:
      1. Locates the toolchain it needs (read-only detection, no installs).
      2. Injects required cross-compile parameters as PROCESS-SCOPED
         environment variables only (they vanish when the script exits and
         never touch the user/system environment).
      3. Builds easytier-ffi, then easytier-android-jni, per ABI via cargo-ndk.
      4. Backs up current .so files and copies fresh ones into app/src/main/jniLibs.

    This script NEVER performs globally-scoped actions: no installs (pip, cargo,
    downloading protoc/libclang), no `rustup target add`, no writes to the user
    or machine environment, no changes to $PROFILE. If a required tool is
    missing, it stops with instructions instead of installing anything.

    Place this script at the repo root and run:
        .\update-easytier-jni.ps1

.PARAMETER Abis
    Optional list of Android ABIs to build. Defaults to the directories present in
    app/src/main/jniLibs. Valid values: arm64-v8a, armeabi-v7a, x86_64.
    (x86/i686 is deprecated and not supported by this script.)

.EXAMPLE
    .\update-easytier-jni.ps1

.EXAMPLE
    .\update-easytier-jni.ps1 -Abis arm64-v8a
#>
[CmdletBinding()]
param(
    [string[]]$Abis
)

$ErrorActionPreference = 'Stop'

function Write-Step  { Write-Host "==> $args" -ForegroundColor Cyan }
function Write-Done  { Write-Host "[OK] $args" -ForegroundColor Green }
function Write-Warn  { Write-Host "[!!] $args" -ForegroundColor Yellow }
function Write-Fail  { Write-Host "[ERR] $args" -ForegroundColor Red }

$RepoRoot    = Split-Path -Parent $MyInvocation.MyCommand.Path
$CoreRoot    = Join-Path $RepoRoot 'EasyTier-Core'
$JniDir      = Join-Path $CoreRoot 'easytier-contrib\easytier-android-jni'
$FfiDir      = Join-Path $CoreRoot 'easytier-contrib\easytier-ffi'
$JniLibs     = Join-Path $RepoRoot 'app\src\main\jniLibs'
$CargoTarget = Join-Path $CoreRoot 'target'

# cargo-ndk ABI name -> rustup target triple (cargo-ndk writes artifacts to target/<triple>/release/)
# NOTE: x86/i686 is deprecated and intentionally NOT included here.
$AbiMap = @{
    'arm64-v8a'   = 'aarch64-linux-android'
    'armeabi-v7a' = 'armv7-linux-androideabi'
    'x86_64'      = 'x86_64-linux-android'
}

Write-Host '================================================================' -ForegroundColor Cyan
Write-Host ' Update EasyTier Android JNI library' -ForegroundColor Cyan
Write-Host '================================================================' -ForegroundColor Cyan

# --- 1. EasyTier-Core submodule --------------------------------------------
if (-not (Test-Path (Join-Path $JniDir 'Cargo.toml'))) {
    Write-Fail ('EasyTier-Core submodule not initialized: {0}' -f $JniDir)
    Write-Warn 'Run: git submodule update --init --recursive'
    exit 1
}
Write-Done 'EasyTier-Core submodule found'

# --- 2. Toolchain detection (read-only) --------------------------------------
# No tool is installed here; missing tools abort with instructions.
foreach ($cmd in 'cargo', 'rustup') {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Fail "Command not found: $cmd. Install Rust from https://rustup.rs first."
        exit 1
    }
}
Write-Done 'Rust toolchain found'
if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    Write-Fail 'cargo-ndk not found. Install it yourself with:  cargo install cargo-ndk'
    exit 1
}
Write-Done ('cargo-ndk ' + (& cargo-ndk --version))

# Android NDK: env vars first, then the newest under %LOCALAPPDATA%\Android\Sdk\ndk.
$ndkRoot = $env:ANDROID_NDK_ROOT
if (-not $ndkRoot) { $ndkRoot = $env:ANDROID_NDK_HOME }
if (-not $ndkRoot) { $ndkRoot = $env:NDK_HOME }
if (-not $ndkRoot -and $env:LOCALAPPDATA) {
    $sdkNdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk'
    if (Test-Path $sdkNdk) {
        $newest = Get-ChildItem $sdkNdk -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($newest) { $ndkRoot = $newest.FullName }
    }
}
if (-not $ndkRoot -or -not (Test-Path $ndkRoot)) {
    Write-Fail 'Android NDK not found. Install it via Android Studio SDK Manager,'
    Write-Fail 'or set ANDROID_NDK_HOME / ANDROID_NDK_ROOT / NDK_HOME.'
    exit 1
}
Write-Done "Android NDK: $ndkRoot"

# NDK clang + bundled libclang (must exist together).
$ndkBin = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$ndkClang = Join-Path $ndkBin 'clang.exe'
$ndkLibclangDll = Join-Path $ndkBin 'libclang.dll'
if (-not (Test-Path $ndkClang)) {
    Write-Fail "NDK clang not found at: $ndkClang"
    exit 1
}
if (-not (Test-Path $ndkLibclangDll)) {
    Write-Fail "NDK libclang.dll not found at: $ndkLibclangDll"
    exit 1
}
Write-Done "clang: $ndkClang"
Write-Done "libclang: $ndkLibclangDll"

# clang builtin headers (lib/clang/<version>/include) for bindgen/libclang.
$ndkClangVerDir = Get-ChildItem (Join-Path $ndkBin '..\lib\clang') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndkClangVerDir) {
    Write-Fail 'NDK clang version directory not found under <ndk>/toolchains/llvm/prebuilt/windows-x86_64/lib/clang.'
    exit 1
}
$ndkBuiltinInc = Join-Path $ndkClangVerDir.FullName 'include'
$ndkSysrootInc = Join-Path (Join-Path $ndkBin '..\sysroot') 'usr\include'

# protoc (host tool for prost build scripts): env -> PATH -> winget Packages ->
# %LOCALAPPDATA%\protoc. Read-only; never downloaded here.
$protocBin = $env:PROTOC
if (-not $protocBin -or -not (Test-Path $protocBin)) {
    $candidate = Get-Command protoc -ErrorAction SilentlyContinue
    if ($candidate) { $protocBin = $candidate.Source }
}
if (-not $protocBin -or -not (Test-Path $protocBin)) {
    $wingetProto = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter 'protoc.exe' -Depth 3 -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($wingetProto) { $protocBin = $wingetProto.FullName }
}
if (-not $protocBin -or -not (Test-Path $protocBin)) {
    $localProto = Get-ChildItem "$env:LOCALAPPDATA\protoc" -Recurse -Filter 'protoc.exe' -Depth 3 -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($localProto) { $protocBin = $localProto.FullName }
}
if (-not $protocBin -or -not (Test-Path $protocBin)) {
    Write-Fail 'protoc not found (needed by prost/prost-wkt-types build scripts).'
    Write-Warn 'Install it yourself (https://github.com/protocolbuffers/protobuf/releases)'
    Write-Warn 'or set PROTOC to the full path of protoc.exe.'
    exit 1
}
Write-Done "protoc: $protocBin"

# --- 3. ABIs ---------------------------------------------------------------
if (-not $Abis -or $Abis.Count -eq 0) {
    $Abis = Get-ChildItem $JniLibs -Directory -ErrorAction SilentlyContinue | ForEach-Object Name
}
$Abis = @($Abis | Where-Object { $AbiMap.ContainsKey($_) } | Select-Object -Unique)
if ($Abis.Count -eq 0) {
    Write-Fail 'No valid ABI given and none found in app/src/main/jniLibs.'
    exit 1
}
Write-Done ('ABIs: ' + ($Abis -join ', '))

# rustup android targets: read-only check. NOT added automatically (adding
# them modifies the global rustup toolchain); abort with instructions instead.
$installedTargets = @(& rustup target list --installed)
$missingTargets = @($Abis | ForEach-Object { $AbiMap[$_] } | Where-Object { $installedTargets -notcontains $_ })
if ($missingTargets.Count -gt 0) {
    Write-Fail ('Missing rustup targets: ' + ($missingTargets -join ', '))
    Write-Warn 'Install them yourself, e.g.:'
    foreach ($t in $missingTargets) { Write-Warn "  rustup target add $t" }
    exit 1
}
Write-Done 'rustup android targets installed'

# --- 4. Process-scoped environment injection ---------------------------------
# All variables below are set with 'Process' scope: they only exist for the
# duration of this script (and the cargo children it spawns), then disappear.
# Nothing here touches the user or system environment.
[Environment]::SetEnvironmentVariable('ANDROID_NDK_HOME', $ndkRoot, 'Process')
[Environment]::SetEnvironmentVariable('CLANG_PATH', $ndkClang, 'Process')
[Environment]::SetEnvironmentVariable('LIBCLANG_PATH', $ndkBin, 'Process')
[Environment]::SetEnvironmentVariable('PROTOC', $protocBin, 'Process')

# cargo-ndk points CC/CXX/AR at NDK binaries WITHOUT the .exe suffix on
# Windows, which can make cc-rs fail to spawn the tool. Override with explicit
# .exe paths (dashed AND underscored forms: cc-rs checks dashed first while
# cargo-ndk overwrites the underscored one).
$ndkClangCxx = Join-Path $ndkBin 'clang++.exe'
$ndkLlvmAr   = Join-Path $ndkBin 'llvm-ar.exe'
foreach ($abi in $Abis) {
    $triple = $AbiMap[$abi]
    $suffix = $triple.Replace('-', '_')
    foreach ($kind in @('CC', 'CXX', 'AR')) {
        $tool = switch ($kind) { 'CC' { $ndkClang } 'CXX' { $ndkClangCxx } 'AR' { $ndkLlvmAr } }
        [Environment]::SetEnvironmentVariable("${kind}_${triple}", $tool, 'Process')
        [Environment]::SetEnvironmentVariable("${kind}_${suffix}", $tool, 'Process')
    }
}

# bindgen-based crates (e.g. kcp-sys) pass the plain rust triple to clang
# (aarch64-linux-android), which NDK clang rejects with 'Unversioned target
# triples are not supported!'. Override with a versioned target plus the NDK
# system include dirs (libclang does not locate the builtin headers itself).
# Forward-slash paths only: bindgen parses these args with shlex, which
# treats backslashes as escapes. Set both dashed and underscored forms.
$BindgenVersionedTarget = @{
    'aarch64-linux-android'   = 'aarch64-linux-android21'
    'armv7-linux-androideabi' = 'armv7a-linux-androideabi21'
    'x86_64-linux-android'    = 'x86_64-linux-android21'
}
$SysrootArchDir = @{
    'aarch64-linux-android'   = 'aarch64-linux-android'
    'armv7-linux-androideabi' = 'arm-linux-androideabi'
    'x86_64-linux-android'    = 'x86_64-linux-android'
}
$ndkFsInc       = $ndkBuiltinInc.Replace('\', '/')
$ndkSysrootFs   = $ndkSysrootInc.Replace('\', '/')
foreach ($target in $BindgenVersionedTarget.Keys) {
    $versioned = "--target=$($BindgenVersionedTarget[$target]) -isystem $ndkFsInc -isystem $ndkSysrootFs"
    if ($SysrootArchDir.ContainsKey($target)) {
        $versioned += " -isystem $ndkSysrootFs/$($SysrootArchDir[$target])"
    }
    [Environment]::SetEnvironmentVariable(('BINDGEN_EXTRA_CLANG_ARGS_' + $target), $versioned, 'Process')
    [Environment]::SetEnvironmentVariable((('BINDGEN_EXTRA_CLANG_ARGS_' + $target).Replace('-', '_')), $versioned, 'Process')
}
Write-Done 'Cross-compile env injected (process-scoped only)'

# --- 5. Build + copy ---------------------------------------------------------
function Invoke-NdkBuild {
    param([string]$WorkingDir, [string]$Abi, [string]$Label)
    Write-Step "[$Abi] Building $Label ..."
    Push-Location $WorkingDir
    try {
        & cargo ndk -t $Abi build --release
        if ($LASTEXITCODE -ne 0) {
            throw "cargo ndk build failed for $Abi ($Label)"
        }
    } finally {
        Pop-Location
    }
}

$backupRoot = Join-Path $RepoRoot ('.jni_backup_' + (Get-Date -Format 'yyyyMMddHHmmss'))
$failed     = $false

foreach ($abi in $Abis) {
    $rustTarget = $AbiMap[$abi]

    Invoke-NdkBuild -WorkingDir $FfiDir -Abi $abi -Label 'easytier-ffi'
    Invoke-NdkBuild -WorkingDir $JniDir -Abi $abi -Label 'easytier-android-jni'

    $src = Join-Path $CargoTarget (Join-Path $rustTarget ('release\libeasytier_android_jni.so'))
    if (-not (Test-Path $src)) {
        Write-Fail "Build output not found: $src"
        $failed = $true
        continue
    }

    $destDir = Join-Path $JniLibs $abi
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    $dest = Join-Path $destDir 'libeasytier_android_jni.so'

    # Back up the previous .so before overwriting
    if (Test-Path $dest) {
        $bakDir = Join-Path $backupRoot $abi
        New-Item -ItemType Directory -Force -Path $bakDir | Out-Null
        Copy-Item $dest (Join-Path $bakDir 'libeasytier_android_jni.so') -Force
    }

    Copy-Item $src $dest -Force
    $size = (Get-Item $dest).Length
    if ($size -eq 0) {
        Write-Fail "Copied file is empty: $dest"
        $failed = $true
        continue
    }
    Write-Done "[$abi] -> $dest ($size bytes)"
}

if ($failed) {
    Write-Fail 'Build finished with errors.'
    exit 1
}

Write-Host ''
Write-Host '================================================================' -ForegroundColor Green
Write-Host ' Done. libeasytier_android_jni.so updated in app/src/main/jniLibs/' -ForegroundColor Green
Write-Host '================================================================' -ForegroundColor Green
if (Test-Path $backupRoot) {
    Write-Host (' Previous .so files backed up to: ' + $backupRoot) -ForegroundColor Yellow
}
Write-Host ''
Write-Warn 'Note: if the native methods changed in this EasyTier-Core version, also check'
Write-Warn '      EasyTier-Core/easytier-contrib/easytier-android-jni/kotlin/com/easytier/jni/'
Write-Warn '      and sync the wrapper (EasyTierJNI.kt) into app/src/main/java/ if needed.'