# Builds a self-contained ClipForge Studio app image (includes a Java runtime; FFmpeg is not bundled).
# Usage:  powershell -ExecutionPolicy Bypass -File packaging\package.ps1 [-Type app-image|msi|exe]
# msi/exe additionally need the WiX Toolset on PATH.
param([string]$Type = "app-image")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\mvnw.cmd -B -q -Pdist -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    $desktop = Join-Path $root "clipforge-desktop\target"
    $jar = Get-ChildItem "$desktop\clipforge-desktop-*.jar" | Select-Object -First 1
    $version = ($jar.BaseName -replace '^clipforge-desktop-', '')
    $out = Join-Path $root "dist"
    if (Test-Path (Join-Path $out "ClipForge Studio")) { Remove-Item -Recurse -Force (Join-Path $out "ClipForge Studio") }
    jpackage --type $Type --name "ClipForge Studio" --app-version $version --vendor "ClipForge" `
        --dest $out --module-path "$($jar.FullName);$desktop\lib" `
        --module com.clipforge.desktop/com.clipforge.desktop.ClipForgeApp `
        --java-options "--enable-native-access=javafx.graphics" `
        --description "Merge, re-score and compress segmented videos"
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
    Write-Host "Created $Type in $out"
} finally {
    Pop-Location
}