#!/bin/sh
# Builds a self-contained ClipForge Studio app image (Linux/macOS). FFmpeg is not bundled.
set -e
cd "$(dirname "$0")/.."
./mvnw -B -q -Pdist -DskipTests package
JAR=$(ls clipforge-desktop/target/clipforge-desktop-*.jar | head -n 1)
VERSION=$(basename "$JAR" .jar | sed 's/^clipforge-desktop-//')
rm -rf "dist/ClipForge Studio"
jpackage --type "${1:-app-image}" --name "ClipForge Studio" --app-version "$VERSION" --vendor ClipForge \
  --dest dist --module-path "$JAR:clipforge-desktop/target/lib" \
  --module com.clipforge.desktop/com.clipforge.desktop.ClipForgeApp \
  --java-options "--enable-native-access=javafx.graphics" \
  --description "Merge, re-score and compress segmented videos"
echo "Created in dist/"