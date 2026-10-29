#!/usr/bin/env bash
# Builds build/gearhead-stub.apk (package com.google.android.projection.gearhead, versionName 1.2.551000-stub),
# signed with the debug key. Needs only aapt2, zipalign and apksigner from the SDK's build-tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk}"; export PATH="$JAVA_HOME/bin:$PATH"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$ANDROID_HOME"/platforms/*/android.jar | sort -V | tail -1)
KS="${REAU_STUB_KEYSTORE:-$HOME/.android/debug.keystore}"
mkdir -p build
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 34
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --out build/gearhead-stub.apk build/aligned.apk
rm -f build/unsigned.apk build/aligned.apk build/gearhead-stub.apk.idsig
"$BT/aapt2" dump badging build/gearhead-stub.apk | head -3
