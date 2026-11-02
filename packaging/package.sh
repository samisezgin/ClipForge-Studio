#!/bin/sh
# Builds a self-contained ClipForge Studio app image (Linux/macOS). FFmpeg is not bundled.
set -e
cd "$(dirname "$0")/.."
# clean: stale jars of older versions in target/ would end up on the module path twice
./mvnw -B -q -Pdist -DskipTests clean package
VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
JAR="clipforge-desktop/target/clipforge-desktop-$VERSION.jar"
[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }
rm -rf "dist/ClipForge Studio"
# jpackage takes a PNG icon on Linux; macOS would need an .icns file
ICON=""
[ "$(uname)" = "Linux" ] && ICON="--icon packaging/clipforge.png"
jpackage --type "${1:-app-image}" --name "ClipForge Studio" --app-version "$VERSION" --vendor ClipForge \
  --dest dist --module-path "$JAR:clipforge-desktop/target/lib" \
  --module com.clipforge.desktop/com.clipforge.desktop.ClipForgeApp \
  --java-options "--enable-native-access=javafx.graphics,com.clipforge.core,com.microsoft.onnxruntime" \
  $ICON --description "Merge, speed up and compress videos"
echo "Created $VERSION in dist/"