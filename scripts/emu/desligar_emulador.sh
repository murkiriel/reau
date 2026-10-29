#!/usr/bin/env bash
# Desliga o emulador emulator-5580 (e so ele). Nunca usa adb sem -s, nunca mata o servidor do adb.
set -uo pipefail
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL=emulator-5580
if ! "$ADB" devices | awk -v s="$SERIAL" '$1==s {f=1} END {exit !f}'; then
  echo "$SERIAL ja esta desligado"; exit 0
fi
"$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1
for _ in $(seq 1 30); do
  "$ADB" devices | awk -v s="$SERIAL" '$1==s {f=1} END {exit !f}' || { echo "$SERIAL desligado"; exit 0; }
  sleep 1
done
echo "$SERIAL ainda aparece no adb apos 30 s" >&2; exit 1
