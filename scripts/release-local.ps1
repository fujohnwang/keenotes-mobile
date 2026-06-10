# Windows entry point for local release (native PowerShell)
param(
    [Parameter(Mandatory = $false)]
    [string]$Version,

    [switch]$Publish,
    [switch]$Android,
    [switch]$SkipSign,
    [switch]$SkipNotarize,
    [switch]$NoClean,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BashScript = Join-Path $ScriptDir "release-local.sh"

function Show-Help {
    Write-Host @"
KeeNotes local release (Windows)

Usage:
  .\scripts\release-local.ps1 -Version 1.2.3
  .\scripts\release-local.ps1 -Version 1.2.3 -Publish

This script delegates to release-local.sh via Git Bash when available,
or runs the Windows PowerShell build directly.

Options:
  -Version VERSION    Release version (required)
  -Publish            Upload to GitHub releases
  -Android            Also build Android (requires Git Bash + SDK)
  -SkipSign           Skip macOS signing (ignored on Windows)
  -SkipNotarize       Skip macOS notarization (ignored on Windows)
  -NoClean            Skip mvn clean
  -Help               Show this help
"@
}

if ($Help) {
    Show-Help
    exit 0
}

if (-not $Version) {
    Write-Error "Version required. Use -Version 1.2.3"
    exit 1
}

$GitBash = "${env:ProgramFiles}\Git\bin\bash.exe"
if (Test-Path $GitBash) {
    $Args = @("--version", $Version)
    if ($Publish) { $Args += "--publish" }
    if ($Android) { $Args += "--android" }
    if ($SkipSign) { $env:SKIP_SIGN = "1" }
    if ($SkipNotarize) { $env:SKIP_NOTARIZE = "1" }
    if ($NoClean) { $env:SKIP_CLEAN = "1" }

    & $GitBash $BashScript @Args
    exit $LASTEXITCODE
}

# Fallback: Windows-only build without publish/android orchestration
if ($Publish -or $Android) {
    Write-Error "Git Bash is required for -Publish and -Android. Install Git for Windows."
    exit 1
}

if ($NoClean) { $env:SKIP_CLEAN = "1" }

& (Join-Path $ScriptDir "release\lib\windows.ps1") -Version $Version
