# Windows local release build (mirrors .github/workflows/release.yml build-windows job)
param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..\..\..")

$MainJar = "keenotes-mobile-1.0.0-SNAPSHOT.jar"
$MainClass = "org.springframework.boot.loader.launch.JarLauncher"
$Vendor = "Keevol"
$DistDir = Join-Path $RepoRoot "dist"

function Write-ReleaseLog([string]$Message) {
    Write-Host "[release] $Message"
}

function Get-AppVersion([string]$FullVersion) {
    $parts = $FullVersion -split '\.'
    if ($parts.Count -ge 3) {
        return ($parts[0..2] -join '.')
    }
    return $FullVersion
}

function New-BuildInfo([string]$ReleaseVersion) {
    $BuildTime = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $OutDir = Join-Path $RepoRoot "src\main\java\cn\keevol\keenotes\mobilefx\generated"
    $OutFile = Join-Path $OutDir "BuildInfo.java"

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

    @"
package cn.keevol.keenotes.mobilefx.generated;

/**
 * Auto-generated build information.
 * DO NOT EDIT - This file is generated during build.
 */
public final class BuildInfo {
    public static final String VERSION = "$ReleaseVersion";
    public static final String BUILD_TIME = "$BuildTime";

    private BuildInfo() {}
}
"@ | Set-Content -Path $OutFile -Encoding UTF8

    Write-ReleaseLog "Generated BuildInfo.java (version=$ReleaseVersion, build_time=$BuildTime)"
}

$AppVersion = Get-AppVersion $Version

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
New-BuildInfo $Version

Write-ReleaseLog "Building desktop JAR (javafx.platform=win)..."
Push-Location $RepoRoot
try {
    if ($env:SKIP_CLEAN -ne "1") {
        mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=win"
    } else {
        mvn package -Pdesktop -DskipTests "-Djavafx.platform=win"
    }
} finally {
    Pop-Location
}

$JPackageBase = @(
    "--input", (Join-Path $RepoRoot "target"),
    "--name", "KeeNotes",
    "--main-jar", $MainJar,
    "--main-class", $MainClass,
    "--app-version", $AppVersion,
    "--vendor", $Vendor,
    "--icon", (Join-Path $RepoRoot "src\main\resources\icons\app-icon.ico"),
    "--java-options", "-Xmx512m",
    "--win-shortcut",
    "--win-menu",
    "--win-menu-group", "Keevol",
    "--win-dir-chooser",
    "--dest", $DistDir
)

Write-ReleaseLog "Creating Windows EXE installer..."
& jpackage @JPackageBase --type exe

Write-ReleaseLog "Creating Windows MSI installer..."
& jpackage @JPackageBase --type msi

Write-ReleaseLog "Windows artifacts in $DistDir:"
Get-ChildItem $DistDir
