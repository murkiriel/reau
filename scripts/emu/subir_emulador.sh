#!/usr/bin/env bash
# Sobe o AVD reau_test_api34 sem janela, em segundo plano, na porta fixa 5580 (serial emulator-5580),
# espera o boot completo, desliga as animacoes e imprime o serial.
# Todas as chamadas ao adb levam "-s emulator-5580": o celular real do dono nunca e tocado.
# Uso: [REAU_AVD=reau_test_api34_gapps] subir_emulador.sh [--frio] [--gravavel]
#   REAU_AVD:   qual AVD (padrao reau_test_api34; o da imagem google_apis e reau_test_api34_gapps)
#   --frio:     sem snapshot, boot a frio
#   --gravavel: emulator -writable-system (necessario para o stub do Android Auto da imagem; os AVDs *_gapps ja sobem assim)
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
AVD="${REAU_AVD:-reau_test_api34}"
PORT=5580
SERIAL="emulator-$PORT"
ADB="$ANDROID_HOME/platform-tools/adb"
LOG="${REAU_EMU_LOG:-/tmp/reau_emulador_$PORT.log}"
adb_e() { "$ADB" -s "$SERIAL" "$@"; }

estado() { "$ADB" devices | awk -v s="$SERIAL" '$1==s {print $2}'; }

if [ "$(estado)" = "device" ] && [ "$(adb_e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
  echo "ja no ar" >&2; echo "$SERIAL"; exit 0
fi

EXTRA=()
case "$AVD" in *_gapps) EXTRA+=(-writable-system) ;; esac   # o AVD google_apis sempre sobe gravavel: so assim o stub (overlay) vale
for a in "$@"; do
  case "$a" in
    --frio) EXTRA+=(-no-snapshot-load) ;;
    --gravavel) EXTRA+=(-writable-system) ;;   # system/product gravaveis (stub do Android Auto: instalar_stubs.sh)
  esac
done
nohup setsid "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save -no-metrics \
  "${EXTRA[@]}" >"$LOG" 2>&1 </dev/null &
echo "emulador iniciado (pid $!), log em $LOG" >&2

for _ in $(seq 1 120); do [ "$(estado)" = "device" ] && break; sleep 2; done
[ "$(estado)" = "device" ] || { echo "o emulador nao apareceu no adb em 240 s; veja $LOG" >&2; exit 1; }

for _ in $(seq 1 180); do
  [ "$(adb_e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
[ "$(adb_e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { echo "boot nao terminou em 360 s; veja $LOG" >&2; exit 1; }

adb_e shell settings put global window_animation_scale 0
adb_e shell settings put global transition_animation_scale 0
adb_e shell settings put global animator_duration_scale 0
adb_e shell settings put global stay_on_while_plugged_in 7 || true
adb_e shell input keyevent KEYCODE_WAKEUP || true
adb_e shell wm dismiss-keyguard || true
echo "$SERIAL"
