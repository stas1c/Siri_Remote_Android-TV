#!/usr/bin/env bash
# Build the bridge APK and package the Magisk module zip into dist/.
# Needs JDK 17 and the Android SDK (platform 34): set JAVA_HOME / ANDROID_HOME or create local.properties.
set -euo pipefail
cd "$(dirname "$0")"
[ -f local.properties ] || echo "sdk.dir=${ANDROID_HOME:-$HOME/Library/Android/sdk}" > local.properties
./gradlew assembleRelease -q --console=plain
cp app/build/outputs/apk/release/app-release.apk module/system/priv-app/SiriRemoteBridge/SiriRemoteBridge.apk
VER=$(sed -n 's/^version=//p' module/module.prop)
mkdir -p dist && rm -f "dist/SiriRemoteBridge-v$VER-magisk.zip"
(cd module && COPYFILE_DISABLE=1 zip -qr -X "../dist/SiriRemoteBridge-v$VER-magisk.zip" META-INF customize.sh module.prop service.sh system -x '*/.gitkeep')
echo "dist/SiriRemoteBridge-v$VER-magisk.zip"
