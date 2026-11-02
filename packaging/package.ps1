# Builds a self-contained ClipForge Studio app image (includes a Java runtime; FFmpeg is not bundled).
# Usage:  powershell -ExecutionPolicy Bypass -File packaging\package.ps1 [-Type app-image|msi|exe]
# msi/exe additionally need the WiX Toolset on PATH. The version must be numeric (no -SNAPSHOT) for jpackage.
param([string]$Type = "app-image")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # clean: stale jars of older versions in target/ would end up on the module path twice
    & .\mvnw.cmd -B -q -Pdist -DskipTests clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    $version = ([xml](Get-Content (Join-Path $root "pom.xml"))).project.version
    $desktop = Join-Path $root "clipforge-desktop\target"
    $jar = Join-Path $desktop "clipforge-desktop-$version.jar"
    if (-not (Test-Path $jar)) { throw "Missing $jar" }
    $out = Join-Path $root "dist"
    if (Test-Path (Join-Path $out "ClipForge Studio")) { Remove-Item -Recurse -Force (Join-Path $out "ClipForge Studio") }
    jpackage --type $Type --name "ClipForge Studio" --app-version $version --vendor "ClipForge" `
        --dest $out --module-path "$jar;$desktop\lib" `
        --module com.clipforge.desktop/com.clipforge.desktop.ClipForgeApp `
        --java-options "--enable-native-access=javafx.graphics,com.clipforge.core" `
        --icon (Join-Path $PSScriptRoot "clipforge.ico") `
        --description "Merge, speed up and compress videos"
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
    Write-Host "Created $Type $version in $out"
} finally {
    Pop-Location
}